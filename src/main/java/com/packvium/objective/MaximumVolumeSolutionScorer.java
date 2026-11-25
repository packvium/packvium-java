package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.UnpackedItem;
import java.math.BigInteger;

/**
 * Ranks by the share of the requested item volume left unpacked, ahead of how many items that
 * is: fill the space, rather than place the most pieces.
 *
 * <p>{@code unpackedVolumePpm} is {@code ceil(unpacked volume x 10^6 / requested volume)}, in
 * nominal item volume. Rounded up, so an incomplete answer never scores zero and a complete
 * answer still beats every incomplete one. A ratio rather than the raw volume because cubic
 * ticks overflow a long for an ordinary freight container. O(n) for n item instances.
 */
public final class MaximumVolumeSolutionScorer implements SolutionScorer {
    private static final BigInteger SCALE = BigInteger.valueOf(1_000_000);

    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        BigInteger unpacked = BigInteger.ZERO;
        for (UnpackedItem item : solution.unpacked()) {
            unpacked = unpacked.add(item.instance().dimensions().volume());
        }
        BigInteger requested = unpacked;
        for (PackedContainer container : solution.containers()) {
            if (!container.latticeItems().isEmpty()) {
                for (ItemInstance instance : container.latticeItems()) {
                    requested = requested.add(instance.dimensions().volume());
                }
            } else {
                for (Placement placement : container.placements()) {
                    requested = requested.add(placement.instance().dimensions().volume());
                }
            }
        }
        long ppm = requested.signum() == 0 ? 0
                : unpacked.multiply(SCALE).add(requested).subtract(BigInteger.ONE).divide(requested).longValueExact();
        return ObjectiveScore.of(ppm, base.component(0), base.component(1), base.component(2), base.component(3));
    }
}
