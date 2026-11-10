package com.packvium.nested;

import com.packvium.result.PackingResult;
import java.util.List;

/** One packing result per level reached, outermost last. */
public record NestedPackingResult(List<PackingResult> levels) {
    public NestedPackingResult {
        levels = List.copyOf(levels);
    }
}
