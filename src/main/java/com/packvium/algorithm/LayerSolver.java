package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.extension.CandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import java.util.ArrayList;
import java.util.List;

public final class LayerSolver implements SingleContainerSolver {
    private final List<PlacementConstraint> constraints;
    private final CandidateScorer scorer;

    public LayerSolver(List<PlacementConstraint> constraints, CandidateScorer scorer) {
        this.constraints = List.copyOf(constraints);
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "layer";
    }

    /** Every layer start re-sorts by a total key ending in item id, so one ordering is enough. */
    @Override
    public boolean orderInsensitive() {
        return true;
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        List<ItemInstance> ordered = StableSorter.sortBy(items, item -> {
            List<Object> key = new ArrayList<>(ItemOrdering.lead(item, config));
            key.add(-item.dimensions().baseAreaTicks());
            key.add(item.dimensions().volume().negate());
            key.add(-item.weight().ticks());
            key.add(item.id());
            return new SortKey(key);
        });
        return BeamPacker.pack(container, sequence, ordered, config,
                ConstraintSet.defaults(config.minimumSupportRatio(), constraints, List.of()), stats, deadline, scorer);
    }
}
