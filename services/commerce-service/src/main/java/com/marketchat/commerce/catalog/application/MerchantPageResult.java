package com.marketchat.commerce.catalog.application;

import java.math.BigDecimal;
import java.util.List;

/** GET /api/catalog/merchants/{id}: branding, delivery terms for the user's location, and the menu by category. */
public record MerchantPageResult(Info merchant, List<Section> sections) {

    public record Info(
            String id,
            String name,
            String emoji,
            String description,
            String logoUrl,
            String coverUrl,
            List<String> cuisineTags,
            boolean open,
            String todayHours,
            double distanceKm,
            String etaText,
            BigDecimal deliveryFee,
            BigDecimal minOrderAmount,
            boolean delivers,
            boolean acceptsCash,
            boolean acceptsCard) {
    }

    public record Section(String id, String name, List<ProductCardResult> products) {
    }
}
