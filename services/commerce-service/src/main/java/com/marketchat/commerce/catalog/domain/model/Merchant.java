package com.marketchat.commerce.catalog.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record Merchant(
        String id,
        String name,
        MerchantType type,
        String emoji,
        List<String> cuisineTags,
        GeoPoint location,
        int avgPrepMinutes,
        double deliveryRadiusKm,
        BigDecimal baseDeliveryFee,
        BigDecimal feePerKm,
        BigDecimal minOrderAmount,
        boolean acceptsCash,
        boolean acceptsCard,
        OpeningHours hours,
        String description,
        String logoUrl,
        String coverUrl) {

    public Merchant {
        cuisineTags = List.copyOf(cuisineTags);
    }
}
