package com.marketchat.commerce.cart.domain.port;

import com.marketchat.commerce.cart.domain.model.Cart;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository {

    Optional<Cart> findActive(String customerId);

    /** Loads the active cart with a row lock (SELECT ... FOR UPDATE); must run inside a transaction. */
    Optional<Cart> lockActive(String customerId);

    /**
     * Inserts a new cart or updates a saved one if its version is still the loaded one (optimistic lock).
     * On success the cart's version is incremented; otherwise throws CART_VERSION_CONFLICT.
     */
    void save(Cart cart);

    void markCheckedOut(UUID cartId);
}
