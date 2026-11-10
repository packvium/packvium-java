package com.packvium.extension;

import com.packvium.algorithm.SingleContainerSolution;
import com.packvium.domain.Container;
import com.packvium.domain.Nesting;
import com.packvium.support.SortKey;
import java.math.BigInteger;

/** Prefers the container that holds the most items, then the cheapest, then the tightest. */
public final class DefaultContainerSelector implements ContainerSelector {
    @Override
    public SortKey score(Container container, SingleContainerSolution solution) {
        var state = solution.state();
        BigInteger used = state.latticeSummary != null
                ? state.latticeSummary.usedVolume()
                : Nesting.usedVolume(state.placements);
        BigInteger leftover = container.innerDimensions().volume().subtract(used);
        return SortKey.of(-state.placementCount(), container.costMinor(), leftover, container.id());
    }
}
