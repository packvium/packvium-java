package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.extension.CandidateScorer;
import java.util.List;

public final class ExtremePointSolver implements SingleContainerSolver {
    private final List<PlacementConstraint> constraints;
    private final CandidateScorer scorer;

    public ExtremePointSolver(List<PlacementConstraint> constraints, CandidateScorer scorer) {
        this.constraints = List.copyOf(constraints);
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "extreme_points";
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        return BeamPacker.pack(container, sequence, items, config,
                ConstraintSet.defaults(config.minimumSupportRatio(), constraints, List.of()), stats, deadline, scorer);
    }
}
