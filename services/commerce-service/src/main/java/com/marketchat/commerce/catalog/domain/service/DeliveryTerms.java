package com.marketchat.commerce.catalog.domain.service;

import com.marketchat.commerce.catalog.domain.model.Merchant;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Delivery fee and ETA rules (spec §3.2). Pure, deterministic. */
public final class DeliveryTerms {

    private DeliveryTerms() {
    }

    /** base + perKm x distance, rounded to the nearest whole MAD. */
    public static BigDecimal fee(Merchant m, double distanceKm) {
        return m.baseDeliveryFee()
                .add(m.feePerKm().multiply(BigDecimal.valueOf(distanceKm)))
                .setScale(0, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** prep time + 4 min/km, shown as a +-5 min range. */
    public static String etaText(Merchant m, double distanceKm) {
        int mid = m.avgPrepMinutes() + (int) Math.ceil(distanceKm * 4);
        return Math.max(5, mid - 5) + "–" + (mid + 5) + " min";
    }

    public static int etaMinutes(Merchant m, double distanceKm) {
        return m.avgPrepMinutes() + (int) Math.ceil(distanceKm * 4);
    }

    public static boolean delivers(Merchant m, double distanceKm) {
        return distanceKm <= m.deliveryRadiusKm();
    }
}
