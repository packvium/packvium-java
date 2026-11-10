package com.packvium.algorithm;

import com.packvium.domain.ItemInstance;
import java.util.List;

/**
 * One container's packing from a single-container solver.
 *
 * @param exhaustive true only when the search space was explored to the end
 * @param dominantLattice true only when the grid really built this container from its lattice
 *     arithmetic; a delegated beam-search answer is not the provably dominant one the
 *     portfolio short-circuit assumes
 */
public record SingleContainerSolution(
        ContainerState state,
        List<ItemInstance> unpacked,
        boolean exhaustive,
        boolean timeLimitReached,
        boolean dominantLattice) {
    public SingleContainerSolution {
        unpacked = List.copyOf(unpacked);
    }

    public SingleContainerSolution(ContainerState state, List<ItemInstance> unpacked) {
        this(state, unpacked, false, false, false);
    }
}
