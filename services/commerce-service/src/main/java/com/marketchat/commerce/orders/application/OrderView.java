package com.marketchat.commerce.orders.application;

import com.marketchat.commerce.orders.domain.model.Order;
import com.marketchat.commerce.orders.domain.model.OrderStatus;
import com.marketchat.commerce.orders.domain.service.OrderStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order as shown to its owner. {@code paymentUrl} is only present while the card payment is pending. */
public record OrderView(
        UUID id,
        String number,
        String status,
        String paymentMethod,
        String paymentStatus,
        String paymentUrl,
        Merchant merchant,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        Delivery delivery,
        String etaText,
        Instant createdAt,
        boolean cancellable,
        List<History> history) {

    public record Merchant(String id, String name, String emoji, String logoUrl) {
    }

    public record Selection(String group, String label, BigDecimal priceDelta) {
    }

    public record Line(String productId, String name, String imageUrl, String emoji, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, String note, List<Selection> selections) {
    }

    public record Delivery(String label, String line, String city) {
    }

    public record History(String status, Instant at, String actor) {
    }

    public boolean awaitingPayment() {
        return OrderStatus.AWAITING_PAYMENT.name().equals(status);
    }

    static OrderView of(Order o) {
        return new OrderView(o.id(), o.number(), o.status().name(), o.paymentMethod().name(), o.paymentStatus().name(),
                o.status() == OrderStatus.AWAITING_PAYMENT ? o.paymentUrl() : null,
                new Merchant(o.merchant().id(), o.merchant().name(), o.merchant().emoji(), o.merchant().logoUrl()),
                o.lines().stream().map(l -> new Line(l.productId(), l.name(), l.imageUrl(), l.emoji(), l.unitPrice(),
                        l.quantity(), l.lineTotal(), l.note(),
                        l.selections().stream().map(s -> new Selection(s.group(), s.label(), s.priceDelta())).toList()))
                        .toList(),
                o.subtotal(), o.deliveryFee(), o.discount(), o.total(), o.currency(),
                new Delivery(o.delivery().label(), o.delivery().line(), o.delivery().city()), o.etaText(), o.createdAt(),
                OrderStateMachine.customerCanCancel(o),
                o.history().stream().map(h -> new History(h.to().name(), h.at(), h.actor().name())).toList());
    }
}
