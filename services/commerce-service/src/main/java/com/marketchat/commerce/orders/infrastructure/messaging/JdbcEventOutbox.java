package com.marketchat.commerce.orders.infrastructure.messaging;

import com.marketchat.commerce.orders.domain.port.EventOutbox;
import com.marketchat.commerce.orders.domain.port.ProcessedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes events to the outbox table in the caller's transaction, wrapped in the envelope of spec §9.1c:
 * {eventId, type, occurredAt, aggregateId, version, payload}. Also records processed payment events.
 */
@Component
class JdbcEventOutbox implements EventOutbox, ProcessedEvents {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcEventOutbox(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void append(String topic, String aggregateId, String type, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", type);
        envelope.put("occurredAt", now.toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        jdbc.sql("""
                        INSERT INTO outbox_events (id, topic, aggregate_id, type, payload, occurred_at)
                        VALUES (:id, :topic, :aggregateId, :type, CAST(:payload AS jsonb), :occurredAt)""")
                .param("id", eventId).param("topic", topic).param("aggregateId", aggregateId).param("type", type)
                .param("payload", json.writeValueAsString(envelope)).param("occurredAt", Timestamp.from(now))
                .update();
    }

    @Override
    public boolean markProcessed(String eventId) {
        return jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId).update() == 1;
    }
}
