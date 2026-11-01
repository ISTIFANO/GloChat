package com.marketchat.commerce.cart.infrastructure.persistence;

import com.marketchat.commerce.cart.domain.model.Cart;
import com.marketchat.commerce.cart.domain.model.CartLine;
import com.marketchat.commerce.cart.domain.model.DeliveryAddress;
import com.marketchat.commerce.cart.domain.model.PaymentMethod;
import com.marketchat.commerce.cart.domain.port.CartRepository;
import com.marketchat.commerce.shared.domain.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcCartRepository implements CartRepository {

    private static final String SELECT_CART = """
            SELECT id, customer_id, merchant_id, version, delivery_label, delivery_line, delivery_city,
                   delivery_lat, delivery_lng, payment_method
              FROM carts WHERE customer_id = :customerId AND status = 'ACTIVE'""";

    private final JdbcClient jdbc;

    JdbcCartRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Cart> findActive(String customerId) {
        return load(SELECT_CART, customerId);
    }

    @Override
    public Optional<Cart> lockActive(String customerId) {
        return load(SELECT_CART + " FOR UPDATE", customerId);
    }

    @Override
    public void save(Cart cart) {
        DeliveryAddress d = cart.delivery();
        String payment = cart.paymentMethod() == null ? null : cart.paymentMethod().name();
        long newVersion = cart.version() + 1;
        if (!cart.persisted() && cart.version() == 0) {
            try {
                jdbc.sql("""
                                INSERT INTO carts (id, customer_id, merchant_id, version, delivery_label, delivery_line,
                                                   delivery_city, delivery_lat, delivery_lng, payment_method)
                                VALUES (:id, :customerId, :merchantId, :version, :label, :line, :city, :lat, :lng, :payment)""")
                        .param("id", cart.id()).param("customerId", cart.customerId()).param("merchantId", cart.merchantId())
                        .param("version", newVersion)
                        .param("label", d == null ? null : d.label()).param("line", d == null ? null : d.line())
                        .param("city", d == null ? null : d.city()).param("lat", d == null ? null : d.lat())
                        .param("lng", d == null ? null : d.lng()).param("payment", payment)
                        .update();
            } catch (DuplicateKeyException e) {
                // another request created the customer's cart at the same moment
                throw conflict();
            }
        } else {
            int updated = jdbc.sql("""
                            UPDATE carts SET merchant_id = :merchantId, version = :newVersion, delivery_label = :label,
                                   delivery_line = :line, delivery_city = :city, delivery_lat = :lat, delivery_lng = :lng,
                                   payment_method = :payment, updated_at = now()
                             WHERE id = :id AND version = :version AND status = 'ACTIVE'""")
                    .param("merchantId", cart.merchantId()).param("newVersion", newVersion)
                    .param("label", d == null ? null : d.label()).param("line", d == null ? null : d.line())
                    .param("city", d == null ? null : d.city()).param("lat", d == null ? null : d.lat())
                    .param("lng", d == null ? null : d.lng()).param("payment", payment)
                    .param("id", cart.id()).param("version", cart.version())
                    .update();
            if (updated == 0) {
                throw conflict();
            }
            jdbc.sql("DELETE FROM cart_items WHERE cart_id = :id").param("id", cart.id()).update();
        }
        int position = 0;
        for (CartLine l : cart.lines()) {
            jdbc.sql("""
                            INSERT INTO cart_items (id, cart_id, product_id, quantity, note, line_key, position)
                            VALUES (:id, :cartId, :productId, :quantity, :note, :lineKey, :position)""")
                    .param("id", l.id()).param("cartId", cart.id()).param("productId", l.productId())
                    .param("quantity", l.quantity()).param("note", l.note()).param("lineKey", l.lineKey())
                    .param("position", position++)
                    .update();
            for (String valueId : l.optionValueIds()) {
                jdbc.sql("INSERT INTO cart_item_selections (cart_item_id, option_value_id) VALUES (:itemId, :valueId)")
                        .param("itemId", l.id()).param("valueId", valueId).update();
            }
        }
        cart.savedAs(newVersion);
    }

    @Override
    public void markCheckedOut(UUID cartId) {
        jdbc.sql("UPDATE carts SET status = 'CHECKED_OUT', version = version + 1, updated_at = now() WHERE id = :id")
                .param("id", cartId).update();
    }

    private Optional<Cart> load(String sql, String customerId) {
        Optional<CartRow> row = jdbc.sql(sql).param("customerId", customerId).query(JdbcCartRepository::row).optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        CartRow r = row.get();
        Map<UUID, List<String>> selections = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.cart_item_id, s.option_value_id
                          FROM cart_item_selections s JOIN cart_items i ON i.id = s.cart_item_id
                         WHERE i.cart_id = :cartId""")
                .param("cartId", r.id())
                .query((rs, i) -> selections.computeIfAbsent(rs.getObject("cart_item_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getString("option_value_id")))
                .list();
        List<CartLine> lines = jdbc.sql("""
                        SELECT id, product_id, quantity, note FROM cart_items WHERE cart_id = :cartId ORDER BY position""")
                .param("cartId", r.id())
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return CartLine.restore(id, rs.getString("product_id"), selections.getOrDefault(id, List.of()),
                            rs.getString("note"), rs.getInt("quantity"));
                })
                .list();
        return Optional.of(Cart.restore(r.id(), r.customerId(), r.merchantId(), r.version(), lines, r.delivery(), r.payment()));
    }

    private record CartRow(UUID id, String customerId, String merchantId, long version, DeliveryAddress delivery,
                           PaymentMethod payment) {
    }

    private static CartRow row(ResultSet rs, int i) throws SQLException {
        String label = rs.getString("delivery_label");
        DeliveryAddress delivery = label == null ? null : new DeliveryAddress(label, rs.getString("delivery_line"),
                rs.getString("delivery_city"), rs.getDouble("delivery_lat"), rs.getDouble("delivery_lng"));
        String payment = rs.getString("payment_method");
        return new CartRow(rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getString("merchant_id"),
                rs.getLong("version"), delivery, payment == null ? null : PaymentMethod.valueOf(payment));
    }

    private static BusinessException conflict() {
        return new BusinessException("CART_VERSION_CONFLICT", 409, "The cart changed in the meantime, reload it");
    }
}
