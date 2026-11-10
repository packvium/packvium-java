package com.packvium.commerce.catalog;

import java.util.Optional;

public enum ExclusionScope {
    ITEM_CARTON("item_carton"),
    ITEM_PALLET("item_pallet");

    private final String value;

    ExclusionScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ExclusionScope> tryFrom(String value) {
        for (ExclusionScope scope : values()) {
            if (scope.value.equals(value)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
