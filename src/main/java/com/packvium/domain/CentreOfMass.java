package com.packvium.domain;

import java.math.BigInteger;
import java.util.List;

/**
 * How far the weighted centre of mass sits from the container's own centre.
 *
 * <p>Reported as parts per million of the half-extent along whichever of the two horizontal
 * axes (length, width) is worse -- the Chebyshev, not Euclidean, offset, so the result stays
 * exact: a Euclidean distance would need a square root. Every item contributes its
 * own physical centre, weighted by its own mass -- an item's clearance envelope is packing
 * buffer, not part of what it weighs, so position/dimensions are used rather than the
 * envelope. Exact throughout: weight ticks times length ticks routinely exceeds a long.
 */
public final class CentreOfMass {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private CentreOfMass() {
    }

    /** O(n) in the placements. */
    public static long offsetPpm(Dimensions inner, List<Placement> placements) {
        BigInteger totalWeight = BigInteger.ZERO;
        for (Placement placement : placements) {
            totalWeight = totalWeight.add(BigInteger.valueOf(placement.instance().weight().ticks()));
        }
        if (totalWeight.signum() == 0) {
            return 0;
        }
        BigInteger doubledWeightedX = BigInteger.ZERO;
        BigInteger doubledWeightedY = BigInteger.ZERO;
        for (Placement placement : placements) {
            BigInteger weight = BigInteger.valueOf(placement.instance().weight().ticks());
            long doubledCenterX = Math.addExact(Math.multiplyExact(2, placement.position().x()),
                    placement.dimensions().length().ticks());
            long doubledCenterY = Math.addExact(Math.multiplyExact(2, placement.position().y()),
                    placement.dimensions().width().ticks());
            doubledWeightedX = doubledWeightedX.add(weight.multiply(BigInteger.valueOf(doubledCenterX)));
            doubledWeightedY = doubledWeightedY.add(weight.multiply(BigInteger.valueOf(doubledCenterY)));
        }
        long offsetX = axisOffsetPpm(doubledWeightedX, totalWeight, inner.length().ticks());
        long offsetY = axisOffsetPpm(doubledWeightedY, totalWeight, inner.width().ticks());
        return Math.max(offsetX, offsetY);
    }

    static long axisOffsetPpm(BigInteger doubledWeighted, BigInteger totalWeight, long extent) {
        BigInteger reference = totalWeight.multiply(BigInteger.valueOf(extent));
        BigInteger absNumerator = doubledWeighted.subtract(reference).abs();
        return saturatingLong(absNumerator.multiply(MILLION).divide(reference));
    }

    /** PHP's {@code (int)} of an integer string: out-of-range values clamp, never wrap. */
    static long saturatingLong(BigInteger value) {
        if (value.compareTo(LONG_MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(LONG_MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }
}
