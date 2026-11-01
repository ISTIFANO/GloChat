package com.marketchat.commerce.orders.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order: an immutable snapshot of what was bought, at which price, plus its status and payment state. */
public record Order(
        UUID id,
        String number,
        String customerId,
        Merchant merchant,
        OrderStatus status,
        OrderPaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        String paymentSessionId,
        String paymentUrl,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        Delivery delivery,
        String etaText,
        UUID quoteId,
        Instant createdAt,
        List<Line> lines,
        List<StatusChange> history) {

    public record Merchant(String id, String name, String emoji, String logoUrl) {
    }

    public record Delivery(String label, String line, String city, double lat, double lng) {
    }

    public record Selection(String group, String label, BigDecimal priceDelta) {
    }

    public record Line(String productId, String name, String imageUrl, String emoji, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, String note, List<Selection> selections) {
    }

    public record StatusChange(OrderStatus from, OrderStatus to, Instant at, Actor actor) {
    }

    public Order {
        lines = List.copyOf(lines);
        history = List.copyOf(history);
    }
}
