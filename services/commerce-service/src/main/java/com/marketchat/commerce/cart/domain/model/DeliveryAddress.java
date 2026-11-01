package com.marketchat.commerce.cart.domain.model;

import com.marketchat.commerce.shared.domain.BusinessException;

/** Where to deliver: a snapshot copied into the cart (and later the order), with coordinates for distance and fee. */
public record DeliveryAddress(String label, String line, String city, double lat, double lng) {

    public DeliveryAddress {
        label = required(label, "label", 60);
        line = required(line, "line", 200);
        city = required(city, "city", 80);
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BusinessException("INVALID_ADDRESS", 422, "Invalid coordinates");
        }
    }

    private static String required(String value, String field, int max) {
        String v = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
        if (v.isEmpty() || v.length() > max) {
            throw new BusinessException("INVALID_ADDRESS", 422, "Address " + field + " is required (max " + max + " characters)");
        }
        return v;
    }
}
