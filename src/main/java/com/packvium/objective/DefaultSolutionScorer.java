package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.domain.Dimensions;
import com.packvium.domain.PackedContainer;
import java.math.BigInteger;

/**
 * Canonical objective vector -- see docs/OBJECTIVE.md.
 *
 * <p>Five lexicographic keys, ascending, lower is better: unpacked count, container count,
 * total cost, unused volume (ppm, floored per container) and stack height (ppm, floored per
 * container). Every key is an exact integer, so all engines agree bit for bit. O(c).
 */
public final class DefaultSolutionScorer implements SolutionScorer {
    /** Ratios are parts per million so the vector stays exactly comparable. */
    private static final BigInteger SCALE = BigInteger.valueOf(1_000_000);

    @Override
    public ObjectiveScore score(RawSolution solution) {
        long cost = 0;
        long unused = 0;
        long height = 0;
        for (PackedContainer packed : solution.containers()) {
            Dimensions inner = packed.container().innerDimensions();
            cost = Math.addExact(cost, packed.container().costMinor());
            BigInteger volume = inner.volume();
            BigInteger free = volume.subtract(packed.usedVolume());
            unused = Math.addExact(unused, free.multiply(SCALE).divide(volume).longValueExact());
            BigInteger top = BigInteger.valueOf(packed.maxZTicks());
            height = Math.addExact(height,
                    top.multiply(SCALE).divide(BigInteger.valueOf(inner.height().ticks())).longValueExact());
        }
        return ObjectiveScore.of(solution.unpacked().size(), solution.containers().size(), cost, unused, height);
    }
}
