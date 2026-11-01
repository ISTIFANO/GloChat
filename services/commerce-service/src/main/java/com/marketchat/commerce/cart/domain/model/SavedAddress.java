package com.marketchat.commerce.cart.domain.model;

import java.util.UUID;

/** A customer's saved delivery address. */
public record SavedAddress(UUID id, String customerId, DeliveryAddress address) {
}
