package com.marketchat.commerce.orders.domain.service;

import com.marketchat.commerce.orders.domain.model.Actor;
import com.marketchat.commerce.orders.domain.model.Order;
import com.marketchat.commerce.orders.domain.model.OrderPaymentMethod;
import com.marketchat.commerce.orders.domain.model.OrderStatus;
import com.marketchat.commerce.shared.domain.BusinessException;

import java.util.Map;
import java.util.Set;

import static com.marketchat.commerce.orders.domain.model.OrderStatus.ACCEPTED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.AWAITING_PAYMENT;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.CANCELED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.DELIVERED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.ON_THE_WAY;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.PLACED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.PREPARING;

/**
 * Order status machine (spec §7). Invalid transitions throw.
 * AWAITING_PAYMENT -paid-> PLACED -> ACCEPTED -> PREPARING -> ON_THE_WAY -> DELIVERED;
 * AWAITING_PAYMENT -> CANCELED (expired / customer); PLACED -> CANCELED (customer, merchant).
 */
public final class OrderStateMachine {

    private record Edge(OrderStatus from, OrderStatus to) {
    }

    private static final Map<Edge, Set<Actor>> ALLOWED = Map.ofEntries(
            Map.entry(new Edge(AWAITING_PAYMENT, PLACED), Set.of(Actor.PAYMENT)),
            Map.entry(new Edge(AWAITING_PAYMENT, CANCELED), Set.of(Actor.PAYMENT, Actor.SYSTEM, Actor.CUSTOMER)),
            Map.entry(new Edge(PLACED, ACCEPTED), Set.of(Actor.MERCHANT, Actor.SYSTEM)),
            Map.entry(new Edge(PLACED, CANCELED), Set.of(Actor.CUSTOMER, Actor.MERCHANT, Actor.SYSTEM)),
            Map.entry(new Edge(ACCEPTED, PREPARING), Set.of(Actor.MERCHANT, Actor.SYSTEM)),
            Map.entry(new Edge(ACCEPTED, CANCELED), Set.of(Actor.MERCHANT, Actor.SYSTEM)),
            Map.entry(new Edge(PREPARING, ON_THE_WAY), Set.of(Actor.MERCHANT, Actor.SYSTEM)),
            Map.entry(new Edge(ON_THE_WAY, DELIVERED), Set.of(Actor.MERCHANT, Actor.SYSTEM)));

    private OrderStateMachine() {
    }

    public static boolean allowed(OrderStatus from, OrderStatus to, Actor actor) {
        return ALLOWED.getOrDefault(new Edge(from, to), Set.of()).contains(actor);
    }

    public static void check(OrderStatus from, OrderStatus to, Actor actor) {
        if (!allowed(from, to, actor)) {
            throw new BusinessException("INVALID_ORDER_TRANSITION", 409,
                    "Order cannot go from " + from + " to " + to + " (" + actor + ")");
        }
    }

    /**
     * The customer may cancel while the order waits for payment, or once placed if nothing was charged (cash).
     * A paid card order needs a refund: not self-service in the MVP.
     */
    public static boolean customerCanCancel(Order order) {
        return order.status() == AWAITING_PAYMENT
                || (order.status() == PLACED && order.paymentMethod() == OrderPaymentMethod.CASH);
    }
}
