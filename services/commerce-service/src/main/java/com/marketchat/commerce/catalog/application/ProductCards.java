package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;
import com.marketchat.commerce.catalog.domain.model.Merchant;
import com.marketchat.commerce.catalog.domain.model.Product;
import com.marketchat.commerce.catalog.domain.service.DeliveryTerms;

import java.time.ZonedDateTime;

final class ProductCards {

    private ProductCards() {
    }

    static ProductCardResult of(Product p, Merchant m, GeoPoint location, ZonedDateTime now) {
        double distance = round1(m.location().distanceKmTo(location));
        String description = p.description().length() > 80 ? p.description().substring(0, 77) + "…" : p.description();
        return new ProductCardResult(
                p.id(), p.name(), description, p.visual(), p.basePrice(), p.currency(),
                MerchantSummary.of(m), distance, DeliveryTerms.etaText(m, distance), DeliveryTerms.fee(m, distance),
                p.tags(), p.needsConfiguration(), m.hours().isOpenAt(now));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
