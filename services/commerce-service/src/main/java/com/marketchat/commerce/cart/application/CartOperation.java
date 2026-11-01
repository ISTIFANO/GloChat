package com.marketchat.commerce.cart.application;

import java.util.List;
import java.util.UUID;

/** One change in an all-or-nothing batch (same schema as the agent's update_cart tool, spec §8.3). */
public sealed interface CartOperation {

    record Add(String productId, int quantity, List<String> optionValueIds, String note) implements CartOperation {
        public Add {
            optionValueIds = optionValueIds == null ? List.of() : List.copyOf(optionValueIds);
        }
    }

    record Update(UUID lineId, Integer quantity, String note) implements CartOperation {
    }

    record Remove(UUID lineId) implements CartOperation {
    }

    record Clear() implements CartOperation {
    }
}
