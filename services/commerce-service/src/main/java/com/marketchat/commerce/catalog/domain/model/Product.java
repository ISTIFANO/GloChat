package com.marketchat.commerce.catalog.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record Product(
        String id,
        String merchantId,
        String name,
        String description,
        BigDecimal basePrice,
        String currency,
        ProductVisual visual,
        List<String> tags,
        boolean inStock,
        int serves,
        List<OptionGroup> optionGroups) {

    public Product {
        tags = List.copyOf(tags);
        optionGroups = List.copyOf(optionGroups);
    }

    public boolean needsConfiguration() {
        return optionGroups.stream().anyMatch(OptionGroup::required);
    }
}
