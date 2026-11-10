package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.domain.PackedContainer;

/**
 * Ranks by the summed raw achieved stack height ahead of container count, cost and unused
 * volume: for a container whose height is a generous ceiling rather than a real
 * limit, the question is how tall the load ends up, not what fraction it used. O(c).
 */
public final class OpenDimensionSolutionScorer implements SolutionScorer {
    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        long achievedHeight = 0;
        for (PackedContainer packed : solution.containers()) {
            achievedHeight = Math.addExact(achievedHeight, packed.maxZTicks());
        }
        return ObjectiveScore.of(base.component(0), achievedHeight, base.component(1), base.component(2),
                base.component(3));
    }
}
