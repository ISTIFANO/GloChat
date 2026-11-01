package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.GeoPoint;

import java.math.BigDecimal;

/**
 * @param location   where to deliver (required: no location, no delivery terms)
 * @param maxPrice   optional budget per item
 * @param partySize  optional number of people (boosts sharing plates)
 * @param limit      1..20
 */
public record SearchCatalogQuery(String text, GeoPoint location, BigDecimal maxPrice, Integer partySize, int limit) {
}
