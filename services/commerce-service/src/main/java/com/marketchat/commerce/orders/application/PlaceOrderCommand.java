package com.marketchat.commerce.orders.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Everything needed to create an order, taken from a confirmed quote's frozen snapshot. */
public record PlaceOrderCommand(
        UUID quoteId,
        String customerId,
        String merchantId,
        String merchantName,
        String merchantEmoji,
        String merchantLogoUrl,
        String paymentMethod,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        String deliveryLabel,
        String deliveryLine,
        String deliveryCity,
        double deliveryLat,
        double deliveryLng,
        String etaText,
        List<Line> lines) {

    public record Selection(String group, String label, BigDecimal priceDelta) {
    }

    public record Line(String productId, String name, String imageUrl, String emoji, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, String note, List<Selection> selections) {
    }
}
