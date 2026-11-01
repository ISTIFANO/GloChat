package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.catalog.domain.model.Merchant;

public record MerchantSummary(String id, String name, String emoji, String cuisine) {

    static MerchantSummary of(Merchant m) {
        return new MerchantSummary(m.id(), m.name(), m.emoji(), m.cuisineTags().isEmpty() ? "" : m.cuisineTags().get(0));
    }
}
