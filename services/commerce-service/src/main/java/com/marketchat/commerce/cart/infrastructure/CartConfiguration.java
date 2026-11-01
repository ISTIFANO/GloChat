package com.marketchat.commerce.cart.infrastructure;

import com.marketchat.commerce.cart.application.CartService;
import com.marketchat.commerce.cart.domain.port.AddressRepository;
import com.marketchat.commerce.cart.domain.port.CartRepository;
import com.marketchat.commerce.catalog.application.CatalogPricing;
import com.marketchat.commerce.shared.application.Transactions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
class CartConfiguration {

    @Bean
    CartService cartService(CartRepository carts, AddressRepository addresses, CatalogPricing pricing, Transactions tx) {
        return new CartService(carts, addresses, pricing, tx, UUID::randomUUID);
    }
}
