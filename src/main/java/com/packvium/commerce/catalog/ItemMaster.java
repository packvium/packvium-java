package com.packvium.commerce.catalog;

import java.util.List;

/** A first-party SKU master record one catalog version pins. */
public record ItemMaster(String id, List<Long> dimensionsMm, long weightG, String description) implements MasterEntry {
    public ItemMaster {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("item id is required");
        }
        dimensionsMm = List.copyOf(dimensionsMm);
        if (dimensionsMm.size() != 3) {
            throw new IllegalArgumentException("item dimensions must have exactly three axes");
        }
        for (long axis : dimensionsMm) {
            if (axis <= 0) {
                throw new IllegalArgumentException("item dimensions must be positive");
            }
        }
        if (weightG <= 0) {
            throw new IllegalArgumentException("item weight must be positive");
        }
    }

    public ItemMaster(String id, List<Long> dimensionsMm, long weightG) {
        this(id, dimensionsMm, weightG, "");
    }
}
