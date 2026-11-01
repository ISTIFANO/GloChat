package com.marketchat.payment.domain.port;

import com.marketchat.payment.domain.model.Payment;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence for payments, webhook idempotency and the payment.events outbox (one database: payment_db). */
public interface PaymentStore {

    String PAYMENT_EVENTS = "payment.events";

    Optional<Payment> findByOrder(UUID orderId);

    /** Row-locked; must run inside a transaction. */
    Optional<Payment> lockBySession(String sessionId);

    /** Inserts the order's payment, or replaces a previous (expired/failed) session of the same order. */
    void upsert(Payment payment);

    void complete(UUID paymentId, Payment.Status status, String providerRef);

    /** Records a webhook event id; false when it was already processed. */
    boolean markWebhookProcessed(String eventId, String type);

    void appendEvent(String topic, String aggregateId, String type, Map<String, Object> payload);
}
