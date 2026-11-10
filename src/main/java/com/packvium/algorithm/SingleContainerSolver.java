package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import java.util.List;

public interface SingleContainerSolver {
    String name();

    /** True for a solver that re-sorts its input, so one ordering is all the portfolio needs to try. */
    default boolean orderInsensitive() {
        return false;
    }

    SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline);
}
