package com.marketchat.commerce.cart.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The cart as the customer sees it, re-priced from the catalog right now (spec §5).
 * {@code issues} lists everything that blocks checkout; an empty list means the cart can be quoted.
 */
public record CartView(
        UUID cartId,
        long version,
        String currency,
        Merchant merchant,
        List<Line> lines,
        int itemCount,
        BigDecimal subtotal,
        Delivery delivery,
        BigDecimal minimumOrder,
        BigDecimal total,
        String paymentMethod,
        List<String> acceptedPaymentMethods,
        List<Issue> issues) {

    public record Merchant(String id, String name, String emoji, String logoUrl, boolean open) {
    }

    public record Selection(String groupId, String group, String valueId, String label, BigDecimal priceDelta) {
    }

    public record Line(UUID lineId, String productId, String name, String imageUrl, String emoji, int quantity,
                       String note, List<Selection> selections, BigDecimal unitPrice, BigDecimal lineTotal,
                       boolean available, String unavailableReason) {
    }

    /** Address snapshot plus the terms computed for it (null when the cart has no merchant yet). */
    public record Delivery(String label, String line, String city, double lat, double lng, Double distanceKm,
                           Boolean inZone, BigDecimal fee, String etaText) {
    }

    /** A checkout blocker, e.g. BELOW_MINIMUM_ORDER (with the missing amount) or ITEM_UNAVAILABLE (with the line). */
    public record Issue(String code, BigDecimal missing, UUID lineId) {
        static Issue of(String code) {
            return new Issue(code, null, null);
        }
    }

    public boolean ready() {
        return issues.isEmpty();
    }
}
