package com.marketchat.commerce.orders.domain.port;

import com.marketchat.commerce.orders.domain.model.Actor;
import com.marketchat.commerce.orders.domain.model.Order;
import com.marketchat.commerce.orders.domain.model.OrderStatus;
import com.marketchat.commerce.orders.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    /** Next human-readable number, e.g. MC-26-000123 (database sequence: unique across instances). */
    String nextNumber(int year);

    void insert(Order order);

    Optional<Order> findById(UUID id);

    /** Row-locked read; must run inside a transaction. */
    Optional<Order> lockById(UUID id);

    /** Only the customer's own orders: someone else's number is simply not found. */
    Optional<Order> findByNumber(String customerId, String number);

    List<Order> list(String customerId, int limit);

    void changeStatus(UUID orderId, OrderStatus from, OrderStatus to, PaymentStatus paymentStatus, Actor actor, Instant at);

    void changePaymentStatus(UUID orderId, PaymentStatus paymentStatus, String paymentRef);

    void attachPaymentSession(UUID orderId, String sessionId, String paymentUrl);

    List<UUID> awaitingPaymentSince(Instant before);
}
