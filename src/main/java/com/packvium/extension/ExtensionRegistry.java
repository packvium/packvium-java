package com.packvium.extension;

import com.packvium.algorithm.SingleContainerSolver;
import com.packvium.constraint.PlacementConstraint;
import java.util.List;
import java.util.Optional;

/** Caller-supplied constraints, orderings, solvers and scorers; empty means the built-ins only. */
public record ExtensionRegistry(
        List<PlacementConstraint> placementConstraints,
        List<ItemOrderStrategy> itemOrderStrategies,
        List<SingleContainerSolver> solvers,
        Optional<CandidateScorer> candidateScorer,
        Optional<ContainerSelector> containerSelector) {
    public ExtensionRegistry {
        placementConstraints = List.copyOf(placementConstraints);
        itemOrderStrategies = List.copyOf(itemOrderStrategies);
        solvers = List.copyOf(solvers);
    }

    public static ExtensionRegistry none() {
        return new ExtensionRegistry(List.of(), List.of(), List.of(), Optional.empty(), Optional.empty());
    }

    public static ExtensionRegistry withConstraints(List<PlacementConstraint> constraints) {
        return new ExtensionRegistry(constraints, List.of(), List.of(), Optional.empty(), Optional.empty());
    }
}
