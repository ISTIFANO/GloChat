package com.marketchat.commerce.checkout.infrastructure;

import com.marketchat.commerce.cart.application.CartService;
import com.marketchat.commerce.checkout.application.CheckoutService;
import com.marketchat.commerce.checkout.domain.port.CheckoutRequests;
import com.marketchat.commerce.checkout.domain.port.QuoteRepository;
import com.marketchat.commerce.orders.application.OrderService;
import com.marketchat.commerce.shared.application.Transactions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

@Configuration
class CheckoutConfiguration {

    @Bean
    CheckoutService checkoutService(CartService carts, OrderService orders, QuoteRepository quotes,
                                    CheckoutRequests requests, Transactions tx, Clock clock) {
        return new CheckoutService(carts, orders, quotes, requests, tx, clock, UUID::randomUUID);
    }
}
