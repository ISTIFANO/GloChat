package com.marketchat.commerce.orders.infrastructure.web;

import com.marketchat.commerce.orders.application.OrderService;
import com.marketchat.commerce.orders.application.OrderView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The customer's own orders (spec §9.1). Someone else's order number answers ORDER_NOT_FOUND. */
@RestController
@RequestMapping("/api/orders")
class OrdersController {

    private final OrderService orders;

    OrdersController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    List<OrderView> list(@AuthenticationPrincipal Jwt jwt) {
        return orders.list(jwt.getSubject());
    }

    @GetMapping("/{number}")
    OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return orders.get(jwt.getSubject(), number);
    }

    @PostMapping("/{number}/cancel")
    OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return orders.cancel(jwt.getSubject(), number);
    }

    /** Retry / start the card payment: returns the order with a fresh paymentUrl. */
    @PostMapping("/{number}/pay")
    OrderView pay(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return orders.startPayment(jwt.getSubject(), number);
    }

    /** Back from the payment page: re-checks the payment with the provider (server-to-server) and returns the order. */
    @PostMapping("/{number}/payment/refresh")
    OrderView refreshPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable String number) {
        return orders.refreshPayment(jwt.getSubject(), number);
    }
}
