package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.ProductVisual;

import java.math.BigDecimal;
import java.util.List;

/** What the UI cards and the agent's search tool receive (spec §8.1). */
public record ProductCardResult(
        String productId,
        String name,
        String shortDescription,
        ProductVisual visual,
        BigDecimal price,
        String currency,
        MerchantSummary merchant,
        double distanceKm,
        String etaText,
        BigDecimal deliveryFee,
        List<String> tags,
        boolean needsConfiguration,
        boolean merchantOpen) {
}
