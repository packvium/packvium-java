package com.packvium.commerce.catalog;

import java.util.List;
import java.util.Optional;

/** A first-party pallet master record one catalog version pins. */
public record PalletMaster(String id, List<Long> deckDimensionsMm, long maxPayloadG, Optional<Long> maxStackHeightMm)
        implements MasterEntry {
    public PalletMaster {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("pallet id is required");
        }
        deckDimensionsMm = List.copyOf(deckDimensionsMm);
        if (deckDimensionsMm.size() != 2) {
            throw new IllegalArgumentException("pallet deck dimensions must have exactly two axes");
        }
        for (long axis : deckDimensionsMm) {
            if (axis <= 0) {
                throw new IllegalArgumentException("pallet dimensions must be positive");
            }
        }
        if (maxPayloadG <= 0) {
            throw new IllegalArgumentException("pallet max_payload_g must be positive");
        }
        if (maxStackHeightMm.isPresent() && maxStackHeightMm.get() <= 0) {
            throw new IllegalArgumentException("max_stack_height_mm must be positive");
        }
    }

    public PalletMaster(String id, List<Long> deckDimensionsMm, long maxPayloadG) {
        this(id, deckDimensionsMm, maxPayloadG, Optional.empty());
    }
}
