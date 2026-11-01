package com.marketchat.payment.application;

import com.marketchat.payment.domain.model.Payment;
import com.marketchat.payment.domain.model.PaymentException;
import com.marketchat.payment.domain.port.PaymentProvider;
import com.marketchat.payment.domain.port.PaymentStore;
import com.marketchat.payment.domain.port.WebhookVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Card payments (spec §6.3). A payment only succeeds when the provider says the session is paid AND the amount
 * and currency equal the order total sent by commerce; the outcome is published on payment.events.
 * Two ways to learn the outcome, both idempotent: the signed webhook, and a server-to-server sync.
 */
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class.getName());

    public record CreateSession(UUID orderId, String orderNumber, BigDecimal amount, String currency, String successUrl,
                                String cancelUrl) {
    }

    public record SessionView(String sessionId, String redirectUrl, String status) {
    }

    public record SyncView(String status, BigDecimal amount, String currency, String providerRef) {
    }

    /** Runs work in one database transaction (port, so this class stays framework-free). */
    public interface Tx {
        <T> T run(Supplier<T> work);
    }

    private final PaymentStore store;
    private final PaymentProvider provider;
    private final WebhookVerifier webhooks;
    private final Tx tx;
    private final Clock clock;
    private final Duration sessionTtl;

    public PaymentService(PaymentStore store, PaymentProvider provider, WebhookVerifier webhooks, Tx tx, Clock clock,
                          Duration sessionTtl) {
        this.store = store;
        this.provider = provider;
        this.webhooks = webhooks;
        this.tx = tx;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /** Opens a hosted checkout for an order, or returns the still-valid one (idempotent per order). */
    public SessionView createSession(CreateSession c) {
        if (c.amount() == null || c.amount().signum() <= 0 || c.amount().scale() > 2) {
            throw new PaymentException("INVALID_AMOUNT", 400, "Amount must be positive with at most 2 decimals");
        }
        String currency = c.currency() == null ? "" : c.currency().toUpperCase();
        if (!currency.matches("[A-Z]{3}")) {
            throw new PaymentException("INVALID_CURRENCY", 400, "Currency must be an ISO 4217 code");
        }
        Instant now = clock.instant();
        var existing = store.findByOrder(c.orderId());
        if (existing.isPresent()) {
            Payment p = existing.get();
            if (p.status() == Payment.Status.SUCCEEDED) {
                throw new PaymentException("ALREADY_PAID", 409, "Order " + p.orderNumber() + " is already paid");
            }
            if (p.status() == Payment.Status.PENDING && now.isBefore(p.expiresAt().minusSeconds(60))
                    && p.amount().compareTo(c.amount()) == 0 && p.currency().equals(currency)) {
                return new SessionView(p.sessionId(), p.redirectUrl(), p.status().name());
            }
        }
        Instant expiresAt = now.plus(sessionTtl);
        long minor = Payment.toMinor(c.amount());
        PaymentProvider.CheckoutSession session = provider.createCheckout(new PaymentProvider.CheckoutRequest(
                c.orderId(), c.orderNumber(), minor, currency, c.successUrl(), c.cancelUrl(), expiresAt,
                "mc-" + c.orderId() + "-" + now.getEpochSecond()));
        Payment payment = new Payment(UUID.randomUUID(), c.orderId(), c.orderNumber(), "STRIPE", session.sessionId(),
                session.redirectUrl(), c.amount(), currency, Payment.Status.PENDING, null, expiresAt, now);
        tx.run(() -> {
            store.upsert(payment);
            return null;
        });
        return new SessionView(payment.sessionId(), payment.redirectUrl(), payment.status().name());
    }

    /** Signed provider webhook. Unknown/duplicate events are acknowledged without effect. */
    public String handleWebhook(String payload, String signatureHeader) {
        WebhookVerifier.VerifiedEvent event = webhooks.verify(payload, signatureHeader);
        return tx.run(() -> {
            if (!store.markWebhookProcessed(event.eventId(), event.type())) {
                return "duplicate";
            }
            if (event.session() == null) {
                return "ignored";
            }
            switch (event.type()) {
                case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                    if (event.session().paid()) {
                        apply(event.session());
                    }
                }
                case "checkout.session.expired", "checkout.session.async_payment_failed" -> apply(event.session());
                default -> {
                    return "ignored";
                }
            }
            return "processed";
        });
    }

    /**
     * Server-to-server check with the provider (used when the customer returns from the payment page, and as a
     * fallback when webhooks can't reach us). Never trusts the browser redirect.
     */
    public SyncView sync(UUID orderId) {
        Payment p = store.findByOrder(orderId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND", 404, "No payment for this order"));
        if (p.status() == Payment.Status.PENDING) {
            PaymentProvider.SessionState state = provider.retrieve(p.sessionId());
            if (state.paid() || state.expired()) {
                tx.run(() -> {
                    apply(state);
                    return null;
                });
            }
            p = store.findByOrder(orderId).orElseThrow();
        }
        return new SyncView(p.status().name(), p.amount(), p.currency(), p.providerRef());
    }

    /** Applies a provider session outcome once (locks the payment; a second report is a no-op). */
    private void apply(PaymentProvider.SessionState s) {
        Payment p = store.lockBySession(s.sessionId()).orElse(null);
        if (p == null) {
            LOG.warning("Provider event for unknown session " + s.sessionId());
            return;
        }
        if (p.status() != Payment.Status.PENDING) {
            return;
        }
        if (s.paid()) {
            if (p.matches(s.amountTotalMinor(), s.currency())) {
                store.complete(p.id(), Payment.Status.SUCCEEDED, s.paymentRef());
                publish(p, "PaymentSucceeded", s.paymentRef(), null);
            } else {
                LOG.warning("AMOUNT_MISMATCH for order " + p.orderNumber() + ": expected " + p.amountMinor() + " "
                        + p.currency() + ", provider reported " + s.amountTotalMinor() + " " + s.currency());
                store.complete(p.id(), Payment.Status.AMOUNT_MISMATCH, s.paymentRef());
                publish(p, "PaymentFailed", s.paymentRef(), "AMOUNT_MISMATCH");
            }
        } else if (s.expired()) {
            store.complete(p.id(), Payment.Status.EXPIRED, null);
            publish(p, "PaymentExpired", null, "SESSION_EXPIRED");
        } else if ("unpaid".equals(s.paymentStatus()) && "complete".equals(s.status())) {
            store.complete(p.id(), Payment.Status.FAILED, s.paymentRef());
            publish(p, "PaymentFailed", s.paymentRef(), "ASYNC_PAYMENT_FAILED");
        }
    }

    private void publish(Payment p, String type, String providerRef, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", p.orderId().toString());
        payload.put("orderNumber", p.orderNumber());
        payload.put("amount", p.amount().toPlainString());
        payload.put("currency", p.currency());
        payload.put("provider", p.provider());
        payload.put("providerRef", providerRef);
        payload.put("reason", reason);
        store.appendEvent(PaymentStore.PAYMENT_EVENTS, p.orderId().toString(), type, payload);
    }
}
