package com.marketchat.commerce.orders.application;

import com.marketchat.commerce.orders.domain.model.Actor;
import com.marketchat.commerce.orders.domain.model.Order;
import com.marketchat.commerce.orders.domain.model.OrderPaymentMethod;
import com.marketchat.commerce.orders.domain.model.OrderStatus;
import com.marketchat.commerce.orders.domain.model.PaymentStatus;
import com.marketchat.commerce.orders.domain.port.EventOutbox;
import com.marketchat.commerce.orders.domain.port.OrderRepository;
import com.marketchat.commerce.orders.domain.port.PaymentGateway;
import com.marketchat.commerce.orders.domain.port.ProcessedEvents;
import com.marketchat.commerce.orders.domain.service.OrderStateMachine;
import com.marketchat.commerce.shared.application.Transactions;
import com.marketchat.commerce.shared.domain.BusinessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Orders use cases (spec §6.2 step 4-5, §6.3, §7). Every state change writes an event to the outbox. */
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class.getName());
    static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(30);

    private final OrderRepository orders;
    private final EventOutbox outbox;
    private final PaymentGateway payments;
    private final ProcessedEvents processedEvents;
    private final Transactions tx;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final String webBaseUrl;

    public OrderService(OrderRepository orders, EventOutbox outbox, PaymentGateway payments,
                        ProcessedEvents processedEvents, Transactions tx, Clock clock, Supplier<UUID> ids,
                        String webBaseUrl) {
        this.orders = orders;
        this.outbox = outbox;
        this.payments = payments;
        this.processedEvents = processedEvents;
        this.tx = tx;
        this.clock = clock;
        this.ids = ids;
        this.webBaseUrl = webBaseUrl;
    }

    /** Creates the order (called by checkout inside its transaction): AWAITING_PAYMENT for card, PLACED for cash. */
    public OrderView place(PlaceOrderCommand c) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            OrderPaymentMethod method = OrderPaymentMethod.valueOf(c.paymentMethod());
            OrderStatus status = method == OrderPaymentMethod.CARD ? OrderStatus.AWAITING_PAYMENT : OrderStatus.PLACED;
            PaymentStatus paymentStatus = method == OrderPaymentMethod.CARD ? PaymentStatus.PENDING : PaymentStatus.CASH_ON_DELIVERY;
            int year = now.atZone(ZoneId.of("Africa/Casablanca")).getYear();
            Order order = new Order(ids.get(), orders.nextNumber(year), c.customerId(),
                    new Order.Merchant(c.merchantId(), c.merchantName(), c.merchantEmoji(), c.merchantLogoUrl()),
                    status, method, paymentStatus, null, null, c.subtotal(), c.deliveryFee(), c.discount(), c.total(),
                    c.currency(),
                    new Order.Delivery(c.deliveryLabel(), c.deliveryLine(), c.deliveryCity(), c.deliveryLat(), c.deliveryLng()),
                    c.etaText(), c.quoteId(), now,
                    c.lines().stream().map(l -> new Order.Line(l.productId(), l.name(), l.imageUrl(), l.emoji(),
                            l.unitPrice(), l.quantity(), l.lineTotal(), l.note(),
                            l.selections().stream().map(s -> new Order.Selection(s.group(), s.label(), s.priceDelta()))
                                    .toList())).toList(),
                    List.of(new Order.StatusChange(null, status, now, Actor.CUSTOMER)));
            orders.insert(order);
            Map<String, Object> payload = basePayload(order);
            payload.put("status", status.name());
            payload.put("merchantId", c.merchantId());
            payload.put("paymentMethod", method.name());
            payload.put("total", c.total());
            payload.put("currency", c.currency());
            outbox.append(EventOutbox.ORDER_EVENTS, order.id().toString(), "OrderPlaced", payload);
            return OrderView.of(order);
        });
    }

    public List<OrderView> list(String customerId) {
        return orders.list(customerId, 50).stream().map(OrderView::of).toList();
    }

    public OrderView get(String customerId, String number) {
        return OrderView.of(own(customerId, number));
    }

    public OrderView getById(UUID orderId) {
        return OrderView.of(orders.findById(orderId).orElseThrow(OrderService::notFound));
    }

    public OrderView cancel(String customerId, String number) {
        UUID id = own(customerId, number).id();
        return tx.inTransaction(() -> {
            Order order = orders.lockById(id).orElseThrow(OrderService::notFound);
            if (!OrderStateMachine.customerCanCancel(order)) {
                throw new BusinessException("ORDER_NOT_CANCELLABLE", 409,
                        "This order can no longer be canceled (" + order.status() + ")");
            }
            PaymentStatus payment = order.paymentStatus() == PaymentStatus.PENDING ? PaymentStatus.EXPIRED : order.paymentStatus();
            transition(order, OrderStatus.CANCELED, payment, Actor.CUSTOMER);
            return OrderView.of(orders.findById(id).orElseThrow());
        });
    }

    /**
     * Opens (or re-opens) the card payment of an order awaiting payment and returns the order with its payment URL.
     * Called right after checkout and by "retry payment". Remote call happens outside any transaction.
     */
    public OrderView startPayment(String customerId, String number) {
        Order order = own(customerId, number);
        if (order.status() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("ORDER_NOT_AWAITING_PAYMENT", 409, "This order does not need a payment");
        }
        return openPaymentSession(order);
    }

    /** Best effort after checkout: if payment-service is down the order stays AWAITING_PAYMENT and the app can retry. */
    public OrderView startPaymentQuietly(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(OrderService::notFound);
        try {
            return openPaymentSession(order);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not open the payment session of order " + order.number() + ": " + e.getMessage());
            return OrderView.of(order);
        }
    }

    /**
     * Asks payment-service for the provider-verified state (it checks with Stripe server-to-server) and applies it.
     * Used when the customer comes back from the payment page; the redirect itself is never trusted.
     */
    public OrderView refreshPayment(String customerId, String number) {
        Order order = own(customerId, number);
        if (order.status() == OrderStatus.AWAITING_PAYMENT && order.paymentSessionId() != null) {
            PaymentGateway.State state = payments.sync(order.id());
            switch (state.status()) {
                case "SUCCEEDED" -> paymentSucceeded(order.id(), state.amount(), state.currency(), state.providerRef());
                case "EXPIRED" -> paymentExpired(order.id());
                case "FAILED", "AMOUNT_MISMATCH" -> paymentFailed(order.id(), state.status());
                default -> { /* still pending */ }
            }
        }
        return OrderView.of(orders.findById(order.id()).orElseThrow());
    }

    /** payment.events consumer entry point; at-least-once delivery is made idempotent by the event id. */
    public void onPaymentEvent(String eventId, String type, UUID orderId, BigDecimal amount, String currency,
                               String providerRef, String reason) {
        tx.inTransaction(() -> {
            if (!processedEvents.markProcessed(eventId)) {
                return null;
            }
            switch (type) {
                case "PaymentSucceeded" -> paymentSucceeded(orderId, amount, currency, providerRef);
                case "PaymentExpired" -> paymentExpired(orderId);
                case "PaymentFailed" -> paymentFailed(orderId, reason);
                default -> LOG.info("Ignoring payment event type " + type);
            }
            return null;
        });
    }

    /** Expiry job: card orders still unpaid after 30 minutes are canceled. Returns how many were canceled. */
    public int expireUnpaidOrders() {
        Instant limit = clock.instant().minus(PAYMENT_TIMEOUT);
        int count = 0;
        for (UUID id : orders.awaitingPaymentSince(limit)) {
            count += tx.inTransaction(() -> {
                Order o = orders.lockById(id).orElse(null);
                if (o == null || o.status() != OrderStatus.AWAITING_PAYMENT) {
                    return 0;
                }
                transition(o, OrderStatus.CANCELED, PaymentStatus.EXPIRED, Actor.SYSTEM);
                return 1;
            });
        }
        return count;
    }

    // ── internals ──

    private OrderView openPaymentSession(Order order) {
        String base = webBaseUrl + "/orders/" + order.number();
        PaymentGateway.Session session = payments.createSession(new PaymentGateway.SessionRequest(order.id(),
                order.number(), order.total(), order.currency(), base + "?payment=success", base + "?payment=cancel"));
        tx.inTransaction(() -> {
            orders.attachPaymentSession(order.id(), session.sessionId(), session.redirectUrl());
            return null;
        });
        return OrderView.of(orders.findById(order.id()).orElseThrow());
    }

    private void paymentSucceeded(UUID orderId, BigDecimal amount, String currency, String providerRef) {
        tx.inTransaction(() -> {
            Order o = orders.lockById(orderId).orElse(null);
            if (o == null || o.status() != OrderStatus.AWAITING_PAYMENT) {
                return null;   // already applied (webhook and sync can both report it) or canceled meanwhile
            }
            if (amount == null || amount.compareTo(o.total()) != 0 || !o.currency().equalsIgnoreCase(currency)) {
                LOG.warning("Payment amount mismatch for order " + o.number() + ": got " + amount + " " + currency);
                orders.changePaymentStatus(orderId, PaymentStatus.FAILED, providerRef);
                return null;
            }
            orders.changePaymentStatus(orderId, PaymentStatus.PAID, providerRef);
            transition(o, OrderStatus.PLACED, PaymentStatus.PAID, Actor.PAYMENT);
            return null;
        });
    }

    private void paymentExpired(UUID orderId) {
        tx.inTransaction(() -> {
            Order o = orders.lockById(orderId).orElse(null);
            if (o != null && o.status() == OrderStatus.AWAITING_PAYMENT) {
                transition(o, OrderStatus.CANCELED, PaymentStatus.EXPIRED, Actor.PAYMENT);
            }
            return null;
        });
    }

    private void paymentFailed(UUID orderId, String reason) {
        tx.inTransaction(() -> {
            Order o = orders.lockById(orderId).orElse(null);
            if (o != null && o.status() == OrderStatus.AWAITING_PAYMENT) {
                LOG.warning("Payment failed for order " + o.number() + ": " + reason);
                orders.changePaymentStatus(orderId, PaymentStatus.FAILED, null);
            }
            return null;
        });
    }

    private void transition(Order order, OrderStatus to, PaymentStatus paymentStatus, Actor actor) {
        OrderStateMachine.check(order.status(), to, actor);
        Instant now = clock.instant();
        orders.changeStatus(order.id(), order.status(), to, paymentStatus, actor, now);
        Map<String, Object> payload = basePayload(order);
        payload.put("from", order.status().name());
        payload.put("to", to.name());
        payload.put("actor", actor.name());
        payload.put("at", now.toString());
        outbox.append(EventOutbox.ORDER_EVENTS, order.id().toString(),
                to == OrderStatus.CANCELED ? "OrderCanceled" : "OrderStatusChanged", payload);
    }

    private static Map<String, Object> basePayload(Order o) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", o.id().toString());
        payload.put("number", o.number());
        payload.put("customerId", o.customerId());
        return payload;
    }

    private Order own(String customerId, String number) {
        return orders.findByNumber(customerId, number).orElseThrow(OrderService::notFound);
    }

    /** Same answer for "doesn't exist" and "not yours": order numbers can't be probed (spec §7). */
    private static BusinessException notFound() {
        return new BusinessException("ORDER_NOT_FOUND", 404, "Order not found");
    }
}
