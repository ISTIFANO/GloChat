package com.marketchat.commerce.catalog.domain.service;

import com.marketchat.commerce.catalog.domain.model.OptionGroup;
import com.marketchat.commerce.catalog.domain.model.OptionValue;
import com.marketchat.commerce.catalog.domain.model.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a (product, selected option values) pair and computes its unit price (spec §4). Pure functions:
 * this is the only place a price is computed, used by the cart view, quotes and orders alike.
 */
public final class ProductConfiguration {

    /** A configuration error, with the group name so the UI/agent can ask naturally ("Which size?"). */
    public record Violation(String code, String groupId, String groupName, String valueId) {
    }

    /** A selected value resolved against the product's option groups. */
    public record Selected(OptionGroup group, OptionValue value) {
    }

    private ProductConfiguration() {
    }

    public static List<Violation> validate(Product product, List<String> valueIds) {
        List<Violation> violations = new ArrayList<>();
        Map<String, OptionGroup> groupOfValue = groupOfValue(product);
        Set<String> seen = new HashSet<>();
        Map<String, Integer> countPerGroup = new HashMap<>();

        for (String valueId : valueIds) {
            OptionGroup group = groupOfValue.get(valueId);
            if (group == null || !seen.add(valueId)) {
                violations.add(new Violation("OPTION_NOT_ALLOWED", group == null ? null : group.id(),
                        group == null ? null : group.name(), valueId));
                continue;
            }
            if (!valueOf(group, valueId).available()) {
                violations.add(new Violation("OPTION_UNAVAILABLE", group.id(), group.name(), valueId));
            }
            countPerGroup.merge(group.id(), 1, Integer::sum);
        }
        for (OptionGroup g : product.optionGroups()) {
            int count = countPerGroup.getOrDefault(g.id(), 0);
            int min = g.required() ? Math.max(1, g.minSelect()) : g.minSelect();
            if (count < min) {
                violations.add(new Violation("OPTION_REQUIRED", g.id(), g.name(), null));
            } else if (count > g.maxSelect()) {
                violations.add(new Violation("TOO_MANY_SELECTIONS", g.id(), g.name(), null));
            }
        }
        return violations;
    }

    /** Selected values in the product's group order, then value order (stable display). */
    public static List<Selected> resolve(Product product, List<String> valueIds) {
        Set<String> wanted = Set.copyOf(valueIds);
        List<Selected> out = new ArrayList<>();
        for (OptionGroup g : product.optionGroups()) {
            for (OptionValue v : g.values()) {
                if (wanted.contains(v.id())) {
                    out.add(new Selected(g, v));
                }
            }
        }
        return out;
    }

    /** unitPrice = base price + sum of the selected price deltas. */
    public static BigDecimal unitPrice(Product product, List<String> valueIds) {
        BigDecimal total = product.basePrice();
        for (Selected s : resolve(product, valueIds)) {
            total = total.add(s.value().priceDelta());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, OptionGroup> groupOfValue(Product product) {
        Map<String, OptionGroup> map = new HashMap<>();
        for (OptionGroup g : product.optionGroups()) {
            for (OptionValue v : g.values()) {
                map.putIfAbsent(v.id(), g);
            }
        }
        return map;
    }

    private static OptionValue valueOf(OptionGroup g, String valueId) {
        return g.values().stream().filter(v -> v.id().equals(valueId)).findFirst().orElseThrow();
    }
}
