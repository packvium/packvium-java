package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.domain.UnpackedItem;

/**
 * Ranks by the total declared value of unpacked items ahead of container count, cost and
 * unused volume. Unpacked count still leads, so no trade-off prefers an incomplete
 * answer; value breaks the tie between answers that leave the same number behind. O(u).
 */
public final class MaximumValueSolutionScorer implements SolutionScorer {
    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        long valueForgone = 0;
        for (UnpackedItem item : solution.unpacked()) {
            valueForgone = Math.addExact(valueForgone, item.instance().item().value().orElse(0L));
        }
        return ObjectiveScore.of(base.component(0), valueForgone, base.component(1), base.component(2),
                base.component(3));
    }
}
