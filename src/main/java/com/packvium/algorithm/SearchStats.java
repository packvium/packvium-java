package com.packvium.algorithm;

import com.packvium.result.SolverMetrics;
import java.util.Optional;

/** Per-start mutable work counters; lives only for the duration of one solve. */
public final class SearchStats {
    public long candidatesEvaluated;
    public long placementsAttempted;
    public long candidatePointsConsidered;
    public long collisionChecks;
    public long supportChecks;
    public long spacePartitions;
    public long searchNodesExpanded;

    /**
     * The request-level lower bound on the objective vector, computed at the root of an
     * exact_small or global-beam solve. Deliberately absent from {@link #metrics()}:
     * those metrics are serialised into every result, and a gap is a public result field this
     * release does not yet carry.
     */
    private long[] objectiveLowerBound;

    public SolverMetrics metrics() {
        return new SolverMetrics(candidatePointsConsidered, placementsAttempted, candidatesEvaluated,
                collisionChecks, supportChecks, spacePartitions, searchNodesExpanded);
    }

    public Optional<long[]> objectiveLowerBound() {
        return Optional.ofNullable(objectiveLowerBound).map(long[]::clone);
    }

    public void setObjectiveLowerBound(long[] bound) {
        objectiveLowerBound = bound.clone();
    }
}
