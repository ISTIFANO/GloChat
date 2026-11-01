package com.marketchat.commerce.checkout.infrastructure.persistence;

import com.marketchat.commerce.checkout.domain.model.Quote;
import com.marketchat.commerce.checkout.domain.port.CheckoutRequests;
import com.marketchat.commerce.checkout.domain.port.QuoteRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcQuoteRepository implements QuoteRepository, CheckoutRequests {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcQuoteRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Quote q) {
        jdbc.sql("""
                        INSERT INTO quotes (id, cart_id, cart_version, customer_id, merchant_id, snapshot, subtotal,
                               delivery_fee, discount, total, currency, payment_method, token_hash, status, expires_at,
                               created_at)
                        VALUES (:id, :cartId, :cartVersion, :customerId, :merchantId, CAST(:snapshot AS jsonb), :subtotal,
                               :deliveryFee, :discount, :total, :currency, :paymentMethod, :tokenHash, :status,
                               :expiresAt, :createdAt)""")
                .param("id", q.id()).param("cartId", q.cartId()).param("cartVersion", q.cartVersion())
                .param("customerId", q.customerId()).param("merchantId", q.merchantId())
                .param("snapshot", json.writeValueAsString(q.snapshot())).param("subtotal", q.subtotal())
                .param("deliveryFee", q.deliveryFee()).param("discount", q.discount()).param("total", q.total())
                .param("currency", q.currency()).param("paymentMethod", q.paymentMethod())
                .param("tokenHash", q.tokenHash()).param("status", q.status().name())
                .param("expiresAt", Timestamp.from(q.expiresAt())).param("createdAt", Timestamp.from(q.createdAt()))
                .update();
    }

    @Override
    public Optional<Quote> lock(UUID quoteId) {
        return jdbc.sql("""
                        SELECT id, cart_id, cart_version, customer_id, merchant_id, snapshot::text AS snapshot, subtotal,
                               delivery_fee, discount, total, currency, payment_method, token_hash, status, expires_at,
                               created_at
                          FROM quotes WHERE id = :id FOR UPDATE""")
                .param("id", quoteId)
                .query((rs, i) -> new Quote(rs.getObject("id", UUID.class), rs.getObject("cart_id", UUID.class),
                        rs.getLong("cart_version"), rs.getString("customer_id"), rs.getString("merchant_id"),
                        json.readValue(rs.getString("snapshot"), Quote.Snapshot.class), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("delivery_fee"), rs.getBigDecimal("discount"), rs.getBigDecimal("total"),
                        rs.getString("currency").trim(), rs.getString("payment_method"), rs.getBytes("token_hash"),
                        Quote.Status.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public void invalidateOpen(UUID cartId) {
        jdbc.sql("UPDATE quotes SET status = 'INVALIDATED' WHERE cart_id = :cartId AND status = 'OPEN'")
                .param("cartId", cartId).update();
    }

    @Override
    public void consume(UUID quoteId, UUID orderId) {
        jdbc.sql("UPDATE quotes SET status = 'CONSUMED', order_id = :orderId WHERE id = :id AND status = 'OPEN'")
                .param("orderId", orderId).param("id", quoteId).update();
    }

    @Override
    public Optional<Done> find(UUID idempotencyKey) {
        return jdbc.sql("SELECT idempotency_key, customer_id, quote_id, order_id FROM checkout_requests WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, i) -> new Done(rs.getObject("idempotency_key", UUID.class), rs.getString("customer_id"),
                        rs.getObject("quote_id", UUID.class), rs.getObject("order_id", UUID.class)))
                .optional();
    }

    @Override
    public void record(Done d) {
        jdbc.sql("""
                        INSERT INTO checkout_requests (idempotency_key, customer_id, quote_id, order_id)
                        VALUES (:key, :customerId, :quoteId, :orderId)""")
                .param("key", d.idempotencyKey()).param("customerId", d.customerId()).param("quoteId", d.quoteId())
                .param("orderId", d.orderId())
                .update();
    }
}
