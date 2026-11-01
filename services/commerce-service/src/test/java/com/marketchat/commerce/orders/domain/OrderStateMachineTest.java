package com.marketchat.commerce.orders.domain;

import com.marketchat.commerce.checkout.domain.service.ConfirmationTokens;
import com.marketchat.commerce.orders.domain.model.Actor;
import com.marketchat.commerce.orders.domain.service.OrderStateMachine;
import com.marketchat.commerce.shared.domain.BusinessException;
import org.junit.jupiter.api.Test;

import static com.marketchat.commerce.orders.domain.model.OrderStatus.ACCEPTED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.AWAITING_PAYMENT;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.CANCELED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.DELIVERED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.ON_THE_WAY;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.PLACED;
import static com.marketchat.commerce.orders.domain.model.OrderStatus.PREPARING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    @Test
    void happyPath() {
        assertThat(OrderStateMachine.allowed(AWAITING_PAYMENT, PLACED, Actor.PAYMENT)).isTrue();
        assertThat(OrderStateMachine.allowed(PLACED, ACCEPTED, Actor.MERCHANT)).isTrue();
        assertThat(OrderStateMachine.allowed(ACCEPTED, PREPARING, Actor.MERCHANT)).isTrue();
        assertThat(OrderStateMachine.allowed(PREPARING, ON_THE_WAY, Actor.MERCHANT)).isTrue();
        assertThat(OrderStateMachine.allowed(ON_THE_WAY, DELIVERED, Actor.MERCHANT)).isTrue();
    }

    @Test
    void onlyThePaymentCanMarkAnOrderPaid() {
        assertThat(OrderStateMachine.allowed(AWAITING_PAYMENT, PLACED, Actor.CUSTOMER)).isFalse();
        assertThat(OrderStateMachine.allowed(AWAITING_PAYMENT, PLACED, Actor.SYSTEM)).isFalse();
    }

    @Test
    void customerCannotCancelOnceTheMerchantAccepted() {
        assertThat(OrderStateMachine.allowed(PLACED, CANCELED, Actor.CUSTOMER)).isTrue();
        assertThat(OrderStateMachine.allowed(ACCEPTED, CANCELED, Actor.CUSTOMER)).isFalse();
    }

    @Test
    void noSkippingAndNoGoingBack() {
        assertThatThrownBy(() -> OrderStateMachine.check(PLACED, DELIVERED, Actor.SYSTEM))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_ORDER_TRANSITION");
        assertThat(OrderStateMachine.allowed(DELIVERED, CANCELED, Actor.SYSTEM)).isFalse();
        assertThat(OrderStateMachine.allowed(CANCELED, PLACED, Actor.PAYMENT)).isFalse();
    }

    @Test
    void confirmationTokensAreRandomAndOnlyTheirHashIsComparable() {
        String a = ConfirmationTokens.generate();
        String b = ConfirmationTokens.generate();
        assertThat(a).isNotEqualTo(b).hasSize(22);   // 128 bits, base64url without padding
        byte[] hash = ConfirmationTokens.hash(a);
        assertThat(ConfirmationTokens.matches(a, hash)).isTrue();
        assertThat(ConfirmationTokens.matches(b, hash)).isFalse();
        assertThat(ConfirmationTokens.matches("", hash)).isFalse();
        assertThat(ConfirmationTokens.matches(null, hash)).isFalse();
    }
}
