package com.packvium.algorithm;

import com.packvium.domain.PackedContainer;
import java.util.List;

public record RebalanceResult(List<PackedContainer> containers, List<WeightMove> moves) {
    public RebalanceResult {
        containers = List.copyOf(containers);
        moves = List.copyOf(moves);
    }

    public boolean improved() {
        return !moves.isEmpty();
    }
}
