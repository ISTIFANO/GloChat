package com.marketchat.commerce.catalog.domain.model;

import java.math.BigDecimal;

public record OptionValue(String id, String label, BigDecimal priceDelta, boolean available) {
}
