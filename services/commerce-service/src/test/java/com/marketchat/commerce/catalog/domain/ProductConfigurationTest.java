package com.marketchat.commerce.catalog.domain;

import com.marketchat.commerce.catalog.domain.model.OptionGroup;
import com.marketchat.commerce.catalog.domain.model.OptionValue;
import com.marketchat.commerce.catalog.domain.model.Product;
import com.marketchat.commerce.catalog.domain.model.ProductVisual;
import com.marketchat.commerce.catalog.domain.service.ProductConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductConfigurationTest {

    private final Product burger = new Product("p", "m", "Burger", "", new BigDecimal("65.00"), "MAD",
            new ProductVisual("🍔", "#fff", "#000"), List.of(), true, 1, List.of(
            new OptionGroup("size", "Taille", OptionGroup.Type.SINGLE, 1, 1, true, List.of(
                    new OptionValue("m", "Normal", BigDecimal.ZERO, true),
                    new OptionValue("l", "Large", new BigDecimal("12"), true))),
            new OptionGroup("extras", "Suppléments", OptionGroup.Type.MULTI, 0, 2, false, List.of(
                    new OptionValue("bacon", "Bacon", new BigDecimal("10"), true),
                    new OptionValue("egg", "Oeuf", new BigDecimal("5"), true),
                    new OptionValue("truffle", "Truffe", new BigDecimal("30"), false)))),
            null, null);

    private List<String> codes(List<String> selection) {
        return ProductConfiguration.validate(burger, selection).stream().map(ProductConfiguration.Violation::code).toList();
    }

    @Test
    void validSelectionIsPricedAsBasePlusDeltas() {
        assertThat(codes(List.of("l", "bacon"))).isEmpty();
        assertThat(ProductConfiguration.unitPrice(burger, List.of("l", "bacon"))).isEqualByComparingTo("87.00");
    }

    @Test
    void requiredGroupMustBeChosen() {
        assertThat(ProductConfiguration.validate(burger, List.of("bacon")))
                .singleElement().satisfies(v -> {
                    assertThat(v.code()).isEqualTo("OPTION_REQUIRED");
                    assertThat(v.groupName()).isEqualTo("Taille");
                });
    }

    @Test
    void tooManySelectionsInAGroup() {
        assertThat(codes(List.of("m", "l"))).contains("TOO_MANY_SELECTIONS");
        assertThat(codes(List.of("m", "bacon", "egg"))).isEmpty();
    }

    @Test
    void foreignDuplicateAndUnavailableValuesAreRejected() {
        assertThat(codes(List.of("m", "x-other-product"))).containsExactly("OPTION_NOT_ALLOWED");
        assertThat(codes(List.of("m", "bacon", "bacon"))).containsExactly("OPTION_NOT_ALLOWED");
        assertThat(codes(List.of("m", "truffle"))).containsExactly("OPTION_UNAVAILABLE");
    }
}
