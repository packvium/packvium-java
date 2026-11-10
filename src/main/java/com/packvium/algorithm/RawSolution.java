package com.packvium.algorithm;

import com.packvium.domain.PackedContainer;
import com.packvium.domain.UnpackedItem;
import java.util.List;

/** One start's answer before it is scored, validated and ranked. */
public record RawSolution(
        String solverName,
        List<PackedContainer> containers,
        List<UnpackedItem> unpacked,
        SearchStats stats,
        boolean timeLimitReached,
        boolean effortLimitReached,
        boolean exhaustive) {
    public RawSolution {
        containers = List.copyOf(containers);
        unpacked = List.copyOf(unpacked);
    }
}
