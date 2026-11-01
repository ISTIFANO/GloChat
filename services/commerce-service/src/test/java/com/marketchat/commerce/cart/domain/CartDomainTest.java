package com.marketchat.commerce.cart.domain;

import com.marketchat.commerce.cart.domain.model.Cart;
import com.marketchat.commerce.cart.domain.model.CartLine;
import com.marketchat.commerce.cart.domain.model.DeliveryAddress;
import com.marketchat.commerce.shared.domain.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartDomainTest {

    private final Cart cart = Cart.start(UUID.randomUUID(), "user-1");

    @Test
    void identicalLinesAreMergedWhateverTheOptionOrder() {
        cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of("b", "a"), null);
        CartLine merged = cart.add(UUID.randomUUID(), "p-1", "m-1", 2, List.of("a", "b"), "  ");
        assertThat(cart.lines()).hasSize(1);
        assertThat(merged.quantity()).isEqualTo(3);
        assertThat(merged.optionValueIds()).containsExactly("a", "b");
    }

    @Test
    void differentOptionsOrNotesMakeSeparateLines() {
        cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of("a"), null);
        cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of("b"), null);
        cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of("a"), "sans oignons");
        assertThat(cart.lines()).hasSize(3);
    }

    @Test
    void oneMerchantPerCart() {
        cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of(), null);
        assertThatThrownBy(() -> cart.add(UUID.randomUUID(), "p-9", "m-2", 1, List.of(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CART_MERCHANT_CONFLICT");
        cart.clear();
        cart.add(UUID.randomUUID(), "p-9", "m-2", 1, List.of(), null);
        assertThat(cart.merchantId()).isEqualTo("m-2");
    }

    @Test
    void removingTheLastLineFreesTheMerchant() {
        CartLine l = cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of(), null);
        cart.removeLine(l.id());
        assertThat(cart.merchantId()).isNull();
        cart.add(UUID.randomUUID(), "p-9", "m-2", 1, List.of(), null);
        assertThat(cart.merchantId()).isEqualTo("m-2");
    }

    @Test
    void quantityIsBetween1And50IncludingMerges() {
        assertThatThrownBy(() -> cart.add(UUID.randomUUID(), "p-1", "m-1", 0, List.of(), null))
                .hasMessageContaining("between 1 and 50");
        cart.add(UUID.randomUUID(), "p-1", "m-1", 49, List.of(), null);
        assertThatThrownBy(() -> cart.add(UUID.randomUUID(), "p-1", "m-1", 2, List.of(), null))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_QUANTITY");
    }

    @Test
    void notesAreCleanedAndLimited() {
        CartLine l = cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of(), " sans\u0007 oignons \n");
        assertThat(l.note()).isEqualTo("sans  oignons");
        assertThatThrownBy(() -> cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of(), "x".repeat(201)))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("NOTE_TOO_LONG");
    }

    @Test
    void changingANoteToMatchAnotherLineMergesThem() {
        CartLine a = cart.add(UUID.randomUUID(), "p-1", "m-1", 1, List.of(), "épicé");
        cart.add(UUID.randomUUID(), "p-1", "m-1", 2, List.of(), null);
        cart.updateLine(a.id(), null, "");   // empty note = no note = same as the second line
        assertThat(cart.lines()).singleElement().extracting(CartLine::quantity).isEqualTo(3);
    }

    @Test
    void unknownLineIsAnError() {
        assertThatThrownBy(() -> cart.removeLine(UUID.randomUUID()))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CART_LINE_NOT_FOUND");
    }

    @Test
    void addressNeedsAllFieldsAndValidCoordinates() {
        assertThatThrownBy(() -> new DeliveryAddress("Maison", " ", "Rabat", 34, -6.8))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_ADDRESS");
        assertThatThrownBy(() -> new DeliveryAddress("Maison", "12 rue X", "Rabat", 134, -6.8))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_ADDRESS");
    }
}
