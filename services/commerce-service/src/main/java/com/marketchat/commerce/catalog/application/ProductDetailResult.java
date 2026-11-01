package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.OptionGroup;

import java.util.List;

public record ProductDetailResult(ProductCardResult product, String description, List<OptionGroup> optionGroups) {
}
