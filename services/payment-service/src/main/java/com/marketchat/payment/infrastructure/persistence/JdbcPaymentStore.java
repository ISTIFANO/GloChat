package com.marketchat.payment.infrastructure.persistence;

import com.marketchat.payment.domain.model.Payment;
import com.marketchat.payment.domain.port.PaymentStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPaymentStore implements PaymentStore {

    private static final String SELECT = """
            SELECT id, order_id, order_number, provider, session_id, redirect_url, amount, currency, status,
                   provider_ref, expires_at, created_at FROM payments""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcPaymentStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<Payment> findByOrder(UUID orderId) {
        return jdbc.sql(SELECT + " WHERE order_id = :orderId").param("orderId", orderId).query(JdbcPaymentStore::map).optional();
    }

    @Override
    public Optional<Payment> lockBySession(String sessionId) {
        return jdbc.sql(SELECT + " WHERE session_id = :sid FOR UPDATE").param("sid", sessionId)
                .query(JdbcPaymentStore::map).optional();
    }

    @Override
    public void upsert(Payment p) {
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, order_number, provider, session_id, redirect_url, amount,
                               currency, status, expires_at, created_at, updated_at)
                        VALUES (:id, :orderId, :number, :provider, :sid, :url, :amount, :currency, :status, :expiresAt,
                               :createdAt, :createdAt)
                        ON CONFLICT (order_id) DO UPDATE SET session_id = EXCLUDED.session_id,
                               redirect_url = EXCLUDED.redirect_url, amount = EXCLUDED.amount,
                               currency = EXCLUDED.currency, status = EXCLUDED.status, provider_ref = NULL,
                               expires_at = EXCLUDED.expires_at, updated_at = EXCLUDED.updated_at
                         WHERE payments.status <> 'SUCCEEDED'""")
                .param("id", p.id()).param("orderId", p.orderId()).param("number", p.orderNumber())
                .param("provider", p.provider()).param("sid", p.sessionId()).param("url", p.redirectUrl())
                .param("amount", p.amount()).param("currency", p.currency()).param("status", p.status().name())
                .param("expiresAt", Timestamp.from(p.expiresAt())).param("createdAt", Timestamp.from(p.createdAt()))
                .update();
    }

    @Override
    public void complete(UUID paymentId, Payment.Status status, String providerRef) {
        jdbc.sql("UPDATE payments SET status = :status, provider_ref = :ref, updated_at = now() WHERE id = :id")
                .param("status", status.name()).param("ref", providerRef).param("id", paymentId).update();
    }

    @Override
    public boolean markWebhookProcessed(String eventId, String type) {
        return jdbc.sql("INSERT INTO webhook_events (event_id, type) VALUES (:id, :type) ON CONFLICT DO NOTHING")
                .param("id", eventId).param("type", type).update() == 1;
    }

    @Override
    public void appendEvent(String topic, String aggregateId, String type, Map<String, Object> payload) {
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
                        VALUES (:id, :topic, :aggregateId, :type, CAST(:payload AS jsonb), :at)""")
                .param("id", eventId).param("topic", topic).param("aggregateId", aggregateId).param("type", type)
                .param("payload", json.writeValueAsString(envelope)).param("at", Timestamp.from(now))
                .update();
    }

    private static Payment map(ResultSet rs, int i) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("order_number"), rs.getString("provider"), rs.getString("session_id"),
                rs.getString("redirect_url"), rs.getBigDecimal("amount"), rs.getString("currency").trim(),
                Payment.Status.valueOf(rs.getString("status")), rs.getString("provider_ref"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
