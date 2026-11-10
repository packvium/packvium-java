package com.packvium.objective;

import com.packvium.algorithm.RawSolution;

/**
 * Ranks by total container cost ahead of container count and unused volume. Completeness
 * still dominates: an answer that leaves items unpacked never wins for being cheaper.
 */
public final class LowestCostSolutionScorer implements SolutionScorer {
    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        return ObjectiveScore.of(base.component(0), base.component(2), base.component(1), base.component(3),
                base.component(4));
    }
}
