package com.marketchat.commerce.checkout.domain.port;

import java.util.Optional;
import java.util.UUID;

/** Idempotency of POST /api/checkout/confirm: a replayed key returns the order created the first time. */
public interface CheckoutRequests {

    record Done(UUID idempotencyKey, String customerId, UUID quoteId, UUID orderId) {
    }

    Optional<Done> find(UUID idempotencyKey);

    void record(Done done);
}
