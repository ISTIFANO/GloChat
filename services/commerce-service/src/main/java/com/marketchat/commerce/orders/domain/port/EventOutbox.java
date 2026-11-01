package com.marketchat.commerce.orders.domain.port;

import java.util.Map;

/** Transactional outbox: the event is stored with the state change and relayed to Kafka afterwards. */
public interface EventOutbox {

    String ORDER_EVENTS = "order.events";

    void append(String topic, String aggregateId, String type, Map<String, Object> payload);
}
