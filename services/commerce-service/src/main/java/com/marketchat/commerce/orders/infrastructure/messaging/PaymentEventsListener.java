package com.marketchat.commerce.orders.infrastructure.messaging;

import com.marketchat.commerce.orders.application.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

/** Consumes payment.events (PaymentSucceeded / PaymentFailed / PaymentExpired) from payment-service. */
@Component
class PaymentEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final OrderService orders;
    private final JsonMapper json;

    PaymentEventsListener(OrderService orders, JsonMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @KafkaListener(topics = "payment.events", groupId = "commerce-service",
            autoStartup = "${marketchat.kafka.listeners.enabled:true}")
    void on(String message) {
        JsonNode envelope;
        try {
            envelope = json.readTree(message);
        } catch (RuntimeException e) {
            LOG.warn("Skipping unreadable payment event: {}", e.getMessage());
            return;
        }
        JsonNode p = envelope.path("payload");
        String eventId = envelope.path("eventId").asString(null);
        String orderId = p.path("orderId").asString(null);
        if (eventId == null || orderId == null) {
            LOG.warn("Skipping payment event without eventId/orderId");
            return;
        }
        BigDecimal amount = p.hasNonNull("amount") ? new BigDecimal(p.path("amount").asString()) : null;
        orders.onPaymentEvent(eventId, envelope.path("type").asString(""), UUID.fromString(orderId), amount,
                p.path("currency").asString(null), p.path("providerRef").asString(null), p.path("reason").asString(null));
    }
}
