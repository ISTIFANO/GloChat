package com.marketchat.commerce.orders.infrastructure.persistence;

import com.marketchat.commerce.orders.domain.model.Actor;
import com.marketchat.commerce.orders.domain.model.Order;
import com.marketchat.commerce.orders.domain.model.OrderPaymentMethod;
import com.marketchat.commerce.orders.domain.model.OrderStatus;
import com.marketchat.commerce.orders.domain.model.PaymentStatus;
import com.marketchat.commerce.orders.domain.port.OrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcOrderRepository implements OrderRepository {

    private static final String SELECT = """
            SELECT id, number, customer_id, merchant_id, merchant_name, merchant_emoji, merchant_logo_url, status,
                   payment_method, payment_status, payment_session_id, payment_url, subtotal, delivery_fee, discount,
                   total, currency, delivery_label, delivery_line, delivery_city, delivery_lat, delivery_lng, eta_text,
                   quote_id, created_at
              FROM orders""";

    private final JdbcClient jdbc;

    JdbcOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextNumber(int year) {
        long n = jdbc.sql("SELECT nextval('order_number_seq')").query(Long.class).single();
        return String.format("MC-%02d-%06d", year % 100, n);
    }

    @Override
    public void insert(Order o) {
        jdbc.sql("""
                        INSERT INTO orders (id, number, customer_id, merchant_id, merchant_name, merchant_emoji,
                               merchant_logo_url, status, payment_method, payment_status, subtotal, delivery_fee, discount,
                               total, currency, delivery_label, delivery_line, delivery_city, delivery_lat, delivery_lng,
                               eta_text, quote_id, created_at, updated_at)
                        VALUES (:id, :number, :customerId, :merchantId, :merchantName, :merchantEmoji, :merchantLogo,
                               :status, :paymentMethod, :paymentStatus, :subtotal, :deliveryFee, :discount, :total,
                               :currency, :label, :line, :city, :lat, :lng, :eta, :quoteId, :createdAt, :createdAt)""")
                .param("id", o.id()).param("number", o.number()).param("customerId", o.customerId())
                .param("merchantId", o.merchant().id()).param("merchantName", o.merchant().name())
                .param("merchantEmoji", o.merchant().emoji()).param("merchantLogo", o.merchant().logoUrl())
                .param("status", o.status().name()).param("paymentMethod", o.paymentMethod().name())
                .param("paymentStatus", o.paymentStatus().name()).param("subtotal", o.subtotal())
                .param("deliveryFee", o.deliveryFee()).param("discount", o.discount()).param("total", o.total())
                .param("currency", o.currency()).param("label", o.delivery().label()).param("line", o.delivery().line())
                .param("city", o.delivery().city()).param("lat", o.delivery().lat()).param("lng", o.delivery().lng())
                .param("eta", o.etaText()).param("quoteId", o.quoteId()).param("createdAt", Timestamp.from(o.createdAt()))
                .update();
        int position = 0;
        for (Order.Line l : o.lines()) {
            UUID itemId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO order_items (id, order_id, position, product_id, name_snapshot, image_url, emoji,
                                   unit_price, quantity, line_total, note)
                            VALUES (:id, :orderId, :position, :productId, :name, :imageUrl, :emoji, :unitPrice,
                                   :quantity, :lineTotal, :note)""")
                    .param("id", itemId).param("orderId", o.id()).param("position", position++)
                    .param("productId", l.productId()).param("name", l.name()).param("imageUrl", l.imageUrl())
                    .param("emoji", l.emoji()).param("unitPrice", l.unitPrice()).param("quantity", l.quantity())
                    .param("lineTotal", l.lineTotal()).param("note", l.note())
                    .update();
            int sp = 0;
            for (Order.Selection s : l.selections()) {
                jdbc.sql("""
                                INSERT INTO order_item_selections (order_item_id, position, group_name, label, price_delta)
                                VALUES (:itemId, :position, :group, :label, :delta)""")
                        .param("itemId", itemId).param("position", sp++).param("group", s.group())
                        .param("label", s.label()).param("delta", s.priceDelta())
                        .update();
            }
        }
        for (Order.StatusChange h : o.history()) {
            history(o.id(), h.from(), h.to(), h.actor(), h.at());
        }
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return one(SELECT + " WHERE id = :id", Map.of("id", id));
    }

    @Override
    public Optional<Order> lockById(UUID id) {
        return one(SELECT + " WHERE id = :id FOR UPDATE", Map.of("id", id));
    }

    @Override
    public Optional<Order> findByNumber(String customerId, String number) {
        return one(SELECT + " WHERE number = :number AND customer_id = :customerId",
                Map.of("number", number, "customerId", customerId));
    }

    @Override
    public List<Order> list(String customerId, int limit) {
        List<Row> rows = jdbc.sql(SELECT + " WHERE customer_id = :customerId ORDER BY created_at DESC, number DESC LIMIT :limit")
                .param("customerId", customerId).param("limit", limit).query(JdbcOrderRepository::row).list();
        return rows.stream().map(this::complete).toList();
    }

    @Override
    public void changeStatus(UUID orderId, OrderStatus from, OrderStatus to, PaymentStatus paymentStatus, Actor actor,
                             Instant at) {
        jdbc.sql("UPDATE orders SET status = :to, payment_status = :payment, updated_at = :at WHERE id = :id AND status = :from")
                .param("to", to.name()).param("payment", paymentStatus.name()).param("at", Timestamp.from(at))
                .param("id", orderId).param("from", from.name())
                .update();
        history(orderId, from, to, actor, at);
    }

    @Override
    public void changePaymentStatus(UUID orderId, PaymentStatus paymentStatus, String paymentRef) {
        jdbc.sql("""
                        UPDATE orders SET payment_status = :payment, payment_ref = coalesce(:ref, payment_ref),
                               updated_at = now() WHERE id = :id""")
                .param("payment", paymentStatus.name()).param("ref", paymentRef).param("id", orderId).update();
    }

    @Override
    public void attachPaymentSession(UUID orderId, String sessionId, String paymentUrl) {
        jdbc.sql("UPDATE orders SET payment_session_id = :sid, payment_url = :url, updated_at = now() WHERE id = :id")
                .param("sid", sessionId).param("url", paymentUrl).param("id", orderId).update();
    }

    @Override
    public List<UUID> awaitingPaymentSince(Instant before) {
        return jdbc.sql("SELECT id FROM orders WHERE status = 'AWAITING_PAYMENT' AND created_at < :before")
                .param("before", Timestamp.from(before)).query(UUID.class).list();
    }

    private void history(UUID orderId, OrderStatus from, OrderStatus to, Actor actor, Instant at) {
        jdbc.sql("""
                        INSERT INTO order_status_history (order_id, from_status, to_status, at, actor)
                        VALUES (:orderId, :from, :to, :at, :actor)""")
                .param("orderId", orderId).param("from", from == null ? null : from.name()).param("to", to.name())
                .param("at", Timestamp.from(at)).param("actor", actor.name())
                .update();
    }

    private Optional<Order> one(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        return spec.query(JdbcOrderRepository::row).optional().map(this::complete);
    }

    private record Row(Order order) {
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(new Order(
                rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("customer_id"),
                new Order.Merchant(rs.getString("merchant_id"), rs.getString("merchant_name"),
                        rs.getString("merchant_emoji"), rs.getString("merchant_logo_url")),
                OrderStatus.valueOf(rs.getString("status")), OrderPaymentMethod.valueOf(rs.getString("payment_method")),
                PaymentStatus.valueOf(rs.getString("payment_status")), rs.getString("payment_session_id"),
                rs.getString("payment_url"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("delivery_fee"),
                rs.getBigDecimal("discount"), rs.getBigDecimal("total"), rs.getString("currency").trim(),
                new Order.Delivery(rs.getString("delivery_label"), rs.getString("delivery_line"),
                        rs.getString("delivery_city"), rs.getDouble("delivery_lat"), rs.getDouble("delivery_lng")),
                rs.getString("eta_text"), rs.getObject("quote_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), List.of(), List.of()));
    }

    /** Adds lines, selections and history to a loaded order row. */
    private Order complete(Row row) {
        Order o = row.order();
        Map<UUID, List<Order.Selection>> selections = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.order_item_id, s.group_name, s.label, s.price_delta
                          FROM order_item_selections s JOIN order_items i ON i.id = s.order_item_id
                         WHERE i.order_id = :orderId ORDER BY s.position""")
                .param("orderId", o.id())
                .query((rs, i) -> selections.computeIfAbsent(rs.getObject("order_item_id", UUID.class), k -> new ArrayList<>())
                        .add(new Order.Selection(rs.getString("group_name"), rs.getString("label"), rs.getBigDecimal("price_delta"))))
                .list();
        List<Order.Line> lines = jdbc.sql("""
                        SELECT id, product_id, name_snapshot, image_url, emoji, unit_price, quantity, line_total, note
                          FROM order_items WHERE order_id = :orderId ORDER BY position""")
                .param("orderId", o.id())
                .query((rs, i) -> new Order.Line(rs.getString("product_id"), rs.getString("name_snapshot"),
                        rs.getString("image_url"), rs.getString("emoji"), rs.getBigDecimal("unit_price"),
                        rs.getInt("quantity"), rs.getBigDecimal("line_total"), rs.getString("note"),
                        selections.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
        List<Order.StatusChange> history = jdbc.sql("""
                        SELECT from_status, to_status, at, actor FROM order_status_history WHERE order_id = :orderId ORDER BY id""")
                .param("orderId", o.id())
                .query((rs, i) -> new Order.StatusChange(
                        rs.getString("from_status") == null ? null : OrderStatus.valueOf(rs.getString("from_status")),
                        OrderStatus.valueOf(rs.getString("to_status")), rs.getTimestamp("at").toInstant(),
                        Actor.valueOf(rs.getString("actor"))))
                .list();
        return new Order(o.id(), o.number(), o.customerId(), o.merchant(), o.status(), o.paymentMethod(),
                o.paymentStatus(), o.paymentSessionId(), o.paymentUrl(), o.subtotal(), o.deliveryFee(), o.discount(),
                o.total(), o.currency(), o.delivery(), o.etaText(), o.quoteId(), o.createdAt(), lines, history);
    }
}
