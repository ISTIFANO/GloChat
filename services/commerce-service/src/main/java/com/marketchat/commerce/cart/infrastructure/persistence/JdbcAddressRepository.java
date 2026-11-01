package com.marketchat.commerce.cart.infrastructure.persistence;

import com.marketchat.commerce.cart.domain.model.DeliveryAddress;
import com.marketchat.commerce.cart.domain.model.SavedAddress;
import com.marketchat.commerce.cart.domain.port.AddressRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAddressRepository implements AddressRepository {

    private final JdbcClient jdbc;

    JdbcAddressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SavedAddress> list(String customerId) {
        return jdbc.sql("""
                        SELECT id, customer_id, label, line, city, lat, lng FROM customer_addresses
                         WHERE customer_id = :customerId ORDER BY created_at, id""")
                .param("customerId", customerId).query(JdbcAddressRepository::map).list();
    }

    @Override
    public Optional<SavedAddress> find(String customerId, UUID id) {
        return jdbc.sql("""
                        SELECT id, customer_id, label, line, city, lat, lng FROM customer_addresses
                         WHERE customer_id = :customerId AND id = :id""")
                .param("customerId", customerId).param("id", id).query(JdbcAddressRepository::map).optional();
    }

    @Override
    public void add(SavedAddress a) {
        jdbc.sql("""
                        INSERT INTO customer_addresses (id, customer_id, label, line, city, lat, lng)
                        VALUES (:id, :customerId, :label, :line, :city, :lat, :lng)""")
                .param("id", a.id()).param("customerId", a.customerId()).param("label", a.address().label())
                .param("line", a.address().line()).param("city", a.address().city())
                .param("lat", a.address().lat()).param("lng", a.address().lng())
                .update();
    }

    @Override
    public boolean delete(String customerId, UUID id) {
        return jdbc.sql("DELETE FROM customer_addresses WHERE customer_id = :customerId AND id = :id")
                .param("customerId", customerId).param("id", id).update() == 1;
    }

    private static SavedAddress map(ResultSet rs, int i) throws SQLException {
        return new SavedAddress(rs.getObject("id", UUID.class), rs.getString("customer_id"),
                new DeliveryAddress(rs.getString("label"), rs.getString("line"), rs.getString("city"),
                        rs.getDouble("lat"), rs.getDouble("lng")));
    }
}
