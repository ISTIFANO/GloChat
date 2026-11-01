package com.marketchat.common.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesToTwoDecimals() {
        assertThat(Money.mad("10").amount().toPlainString()).isEqualTo("10.00");
        assertThat(Money.mad("10.005").amount().toPlainString()).isEqualTo("10.01");
    }

    @Test
    void addsAndMultiplies() {
        Money burger = Money.mad("65.00");
        Money cheese = Money.mad("5.00");
        assertThat(burger.plus(cheese).times(2)).isEqualTo(Money.mad("140.00"));
        assertThat(burger.minus(cheese)).isEqualTo(Money.mad("60.00"));
    }

    @Test
    void equalityIgnoresScaleOfInput() {
        assertThat(Money.mad("5")).isEqualTo(Money.mad("5.00"));
    }

    @Test
    void rejectsCurrencyMismatch() {
        assertThatThrownBy(() -> Money.mad("1").plus(Money.of("1", "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> Money.of("1", "mad")).isInstanceOf(IllegalArgumentException.class);
    }
}
