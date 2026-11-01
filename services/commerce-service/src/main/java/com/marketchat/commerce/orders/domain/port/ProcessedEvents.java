package com.marketchat.commerce.orders.domain.port;

/** Consumer-side idempotency: Kafka delivers at least once. */
public interface ProcessedEvents {

    /** Records the event id; false if it was already processed. */
    boolean markProcessed(String eventId);
}
