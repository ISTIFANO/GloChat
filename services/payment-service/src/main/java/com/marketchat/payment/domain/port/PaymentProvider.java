package com.marketchat.payment.domain.port;

import java.time.Instant;
import java.util.UUID;

/** Hosted-checkout payment provider (Stripe in the MVP). Card data never touches our servers. */
public interface PaymentProvider {

    record CheckoutRequest(UUID orderId, String orderNumber, long amountMinor, String currency, String successUrl,
                           String cancelUrl, Instant expiresAt, String idempotencyKey) {
    }

    record CheckoutSession(String sessionId, String redirectUrl) {
    }

    /**
     * Provider-side state of a session, read server-to-server with the secret key.
     *
     * @param status        open, complete or expired
     * @param paymentStatus paid, unpaid or no_payment_required
     */
    record SessionState(String sessionId, String status, String paymentStatus, Long amountTotalMinor, String currency,
                        String paymentRef) {
        public boolean paid() {
            return "complete".equals(status) && "paid".equals(paymentStatus);
        }

        public boolean expired() {
            return "expired".equals(status);
        }
    }

    CheckoutSession createCheckout(CheckoutRequest request);

    SessionState retrieve(String sessionId);
}
