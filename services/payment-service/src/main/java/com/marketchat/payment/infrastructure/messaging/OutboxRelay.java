package com.marketchat.payment.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publishes payment.events from the outbox (key = order id), at least once; commerce deduplicates by eventId. */
@Component
@ConditionalOnProperty(name = "marketchat.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    private record Pending(UUID id, String topic, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${marketchat.outbox.relay.interval-ms:500}")
    @Transactional
    public void relay() {
        List<Pending> batch = jdbc.sql("""
                        SELECT id, topic, aggregate_id, payload::text AS payload FROM outbox_events
                         WHERE published_at IS NULL ORDER BY occurred_at, id LIMIT 100 FOR UPDATE SKIP LOCKED""")
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("aggregate_id"), rs.getString("payload")))
                .list();
        for (Pending p : batch) {
            try {
                kafka.send(p.topic(), p.key(), p.payload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("Outbox relay could not publish event {} to {}: {}", p.id(), p.topic(), e.getMessage());
                return;
            }
            jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id").param("id", p.id()).update();
        }
    }
}
