package com.marketchat.commerce.catalog.domain.model;

import java.util.List;

public record OptionGroup(String id, String name, Type type, int minSelect, int maxSelect, boolean required,
                          List<OptionValue> values) {

    public enum Type { SINGLE, MULTI }

    public OptionGroup {
        values = List.copyOf(values);
    }
}
