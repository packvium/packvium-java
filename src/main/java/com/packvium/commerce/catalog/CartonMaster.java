package com.packvium.commerce.catalog;

import java.util.List;

/** A first-party carton (box) master record one catalog version pins. */
public record CartonMaster(String id, List<Long> innerDimensionsMm, long maxPayloadG, long costMinor) implements MasterEntry {
    public CartonMaster {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("carton id is required");
        }
        innerDimensionsMm = List.copyOf(innerDimensionsMm);
        if (innerDimensionsMm.size() != 3) {
            throw new IllegalArgumentException("carton dimensions must have exactly three axes");
        }
        for (long axis : innerDimensionsMm) {
            if (axis <= 0) {
                throw new IllegalArgumentException("carton dimensions must be positive");
            }
        }
        if (maxPayloadG <= 0) {
            throw new IllegalArgumentException("carton max_payload_g must be positive");
        }
        if (costMinor < 0) {
            throw new IllegalArgumentException("cost_minor cannot be negative");
        }
    }

    public CartonMaster(String id, List<Long> innerDimensionsMm, long maxPayloadG) {
        this(id, innerDimensionsMm, maxPayloadG, 0);
    }
}
