package com.packvium.extension;

import com.packvium.algorithm.SingleContainerSolution;
import com.packvium.domain.Container;
import com.packvium.support.SortKey;

/** Ranks one container's packing against the other offered containers; lower wins. */
public interface ContainerSelector {
    SortKey score(Container container, SingleContainerSolution solution);
}
