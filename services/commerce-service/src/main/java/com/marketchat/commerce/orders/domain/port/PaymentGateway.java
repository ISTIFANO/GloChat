package com.marketchat.commerce.orders.domain.port;

import java.math.BigDecimal;
import java.util.UUID;

/** payment-service, seen from commerce (POST /internal/payments/...). The amount always comes from the order. */
public interface PaymentGateway {

    record SessionRequest(UUID orderId, String orderNumber, BigDecimal amount, String currency, String successUrl,
                          String cancelUrl) {
    }

    record Session(String sessionId, String redirectUrl) {
    }

    /** Provider-verified state of an order's payment. status: PENDING, SUCCEEDED, FAILED, EXPIRED, AMOUNT_MISMATCH. */
    record State(String status, BigDecimal amount, String currency, String providerRef) {
    }

    Session createSession(SessionRequest request);

    State sync(UUID orderId);
}
