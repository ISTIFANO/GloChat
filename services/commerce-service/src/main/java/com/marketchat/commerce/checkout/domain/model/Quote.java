package com.marketchat.commerce.checkout.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A frozen price for the cart at a given version (spec §6.1). Valid 10 minutes; consumed by exactly one order.
 * Only the SHA-256 of the confirmation token is kept.
 */
public record Quote(
        UUID id,
        UUID cartId,
        long cartVersion,
        String customerId,
        String merchantId,
        Snapshot snapshot,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        String paymentMethod,
        byte[] tokenHash,
        Status status,
        Instant expiresAt,
        Instant createdAt) {

    public enum Status { OPEN, CONSUMED, EXPIRED, INVALIDATED }

    public record Snapshot(Merchant merchant, List<Line> lines, Delivery delivery) {
        public Snapshot {
            lines = List.copyOf(lines);
        }
    }

    public record Merchant(String id, String name, String emoji, String logoUrl) {
    }

    public record Selection(String group, String label, BigDecimal priceDelta) {
    }

    public record Line(String productId, String name, String imageUrl, String emoji, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, String note, List<Selection> selections) {
        public Line {
            selections = List.copyOf(selections);
        }
    }

    public record Delivery(String label, String line, String city, double lat, double lng, Double distanceKm,
                           String etaText) {
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
