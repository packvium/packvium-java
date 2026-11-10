package com.packvium.algorithm;

import com.packvium.domain.ItemInstance;
import java.util.List;

/** One portfolio start: a solver, the name of the item order it runs on, and that order. */
record Start(SingleContainerSolver solver, String orderName, List<ItemInstance> order) {
    Start {
        order = List.copyOf(order);
    }

    String id() {
        return solver.name() + ":" + orderName;
    }
}
