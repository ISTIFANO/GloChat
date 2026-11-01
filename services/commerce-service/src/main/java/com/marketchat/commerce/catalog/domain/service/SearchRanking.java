package com.marketchat.commerce.catalog.domain.service;

/**
 * Deterministic ranking (spec §2.4, MVP subset): text relevance first, then budget fit and speed.
 * Popularity and personal affinity are added once orders exist.
 */
public final class SearchRanking {

    private SearchRanking() {
    }

    /**
     * @param textScore   0..1 relevance from the search engine
     * @param price       product price
     * @param maxPrice    user budget or null
     * @param etaMinutes  estimated delivery minutes
     */
    public static double score(double textScore, double price, Double maxPrice, int etaMinutes) {
        double budgetFit = maxPrice == null || maxPrice <= 0 ? 0.5 : Math.max(0, 1 - price / maxPrice) * 0.5 + 0.5;
        double speed = Math.max(0, 1 - etaMinutes / 60.0);
        return 0.65 * clamp(textScore) + 0.20 * budgetFit + 0.15 * speed;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
