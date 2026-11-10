package com.packvium.constraint;

import com.packvium.domain.Axle;
import com.packvium.domain.AxlePair;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Two-axle weight distribution.
 *
 * <p>Two-point beam statics: taking moments about each axle in turn gives an exact fraction for
 * what the other axle carries, with no assumption weaker than "the container behaves like a
 * rigid beam resting on exactly two supports". Exact throughout: weight ticks times length
 * ticks routinely exceeds a long for a heavy, metre-scale load. Both loads are compared to
 * their limits by cross-multiplying the same exact fraction, never rounded to an intermediate
 * axle load. O(n) in the units.
 */
public final class AxleLoad {
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private AxleLoad() {
    }

    /** Front and rear reactions, each {@code numerator / denominator}; numerators may be negative. */
    public record Reactions(BigInteger denominator, BigInteger frontNumerator, BigInteger rearNumerator) {
    }

    public static Reactions reactions(AxlePair axles, List<LoadUnit> units) {
        return reactions(axles, units, 0, 0);
    }

    /**
     * Takes LoadUnits rather than Placements -- a placement-time candidate does not have one yet.
     * A unit's box is its envelope, whose centre coincides with the physical item's because
     * clearance pads both sides of every axis equally.
     */
    public static Reactions reactions(AxlePair axles, List<LoadUnit> units, long tareWeightTicks,
            long tareDoubledCenterX) {
        BigInteger frontX = big(axles.front().position().ticks());
        BigInteger rearX = big(axles.rear().position().ticks());
        BigInteger span = rearX.subtract(frontX);
        BigInteger totalWeight = totalWeight(units, tareWeightTicks);
        BigInteger doubledWeightedX = doubledWeightedX(units, tareWeightTicks, tareDoubledCenterX);
        BigInteger twoTotal = TWO.multiply(totalWeight);
        return new Reactions(TWO.multiply(span), twoTotal.multiply(rearX).subtract(doubledWeightedX),
                doubledWeightedX.subtract(twoTotal.multiply(frontX)));
    }

    public static Optional<Violation> exceeded(AxlePair axles, List<LoadUnit> units) {
        return exceeded(axles, units, 0, 0);
    }

    public static Optional<Violation> exceeded(AxlePair axles, List<LoadUnit> units, long tareWeightTicks,
            long tareDoubledCenterX) {
        Reactions reaction = reactions(axles, units, tareWeightTicks, tareDoubledCenterX);
        if (overLimit(axles.front(), reaction.frontNumerator(), reaction.denominator())) {
            return Optional.of(new Violation("axle_overloaded", "front"));
        }
        if (overLimit(axles.rear(), reaction.rearNumerator(), reaction.denominator())) {
            return Optional.of(new Violation("axle_overloaded", "rear"));
        }
        return Optional.empty();
    }

    private static boolean overLimit(Axle axle, BigInteger numerator, BigInteger denominator) {
        if (axle.maxLoad().isEmpty() || numerator.signum() < 0) {
            return false;
        }
        return numerator.compareTo(big(axle.maxLoad().get().ticks()).multiply(denominator)) > 0;
    }

    /**
     * The tightest x-origins that seat this item exactly on either axle's limit.
     *
     * <p>Candidate generation only proposes positions flush against a wall or another box's
     * corner, which is incomplete once axle limits are in play: sometimes the only feasible
     * spot floats away from every wall, specifically to keep this item's own moment from
     * tipping one axle over its limit. Solving the boundary equation of {@link #reactions} for
     * this item's centre turns the two limits into two extra x-origins worth trying. Both are
     * biased toward the safe side of their own limit, and the caller runs every candidate
     * through the same collision and constraint checks regardless.
     */
    public static List<Long> axleBalancedOrigins(AxlePair axles, List<LoadUnit> otherUnits, long tareWeightTicks,
            long tareDoubledCenterX, long itemWeightTicks, long itemLengthTicks) {
        if (itemWeightTicks <= 0) {
            return List.of();
        }
        BigInteger frontX = big(axles.front().position().ticks());
        BigInteger rearX = big(axles.rear().position().ticks());
        BigInteger denominator = TWO.multiply(rearX.subtract(frontX));
        BigInteger otherDoubledX = doubledWeightedX(otherUnits, tareWeightTicks, tareDoubledCenterX);
        BigInteger twoTotal = TWO.multiply(totalWeight(otherUnits, tareWeightTicks).add(big(itemWeightTicks)));
        BigInteger itemWeight = big(itemWeightTicks);
        BigInteger itemLength = big(itemLengthTicks);
        List<Long> origins = new ArrayList<>(2);
        if (axles.front().maxLoad().isPresent()) {
            // Smallest doubled centre for which front_numerator <= front limit * denominator.
            BigInteger required = twoTotal.multiply(rearX)
                    .subtract(big(axles.front().maxLoad().get().ticks()).multiply(denominator))
                    .subtract(otherDoubledX);
            BigInteger doubledCentre = ceilDiv(required, itemWeight); // never understate what front needs
            origins.add(saturatingLong(ceilDiv(doubledCentre.subtract(itemLength), TWO))); // stay on the safe side
        }
        if (axles.rear().maxLoad().isPresent()) {
            // Largest doubled centre for which rear_numerator <= rear limit * denominator.
            BigInteger allowed = big(axles.rear().maxLoad().get().ticks()).multiply(denominator)
                    .add(twoTotal.multiply(frontX))
                    .subtract(otherDoubledX);
            BigInteger doubledCentre = floorDiv(allowed, itemWeight); // never overstate what rear allows
            origins.add(saturatingLong(floorDiv(doubledCentre.subtract(itemLength), TWO))); // stay on the safe side
        }
        return List.copyOf(origins);
    }

    private static BigInteger totalWeight(List<LoadUnit> units, long tareWeightTicks) {
        BigInteger total = big(tareWeightTicks);
        for (LoadUnit unit : units) {
            total = total.add(big(unit.weightTicks()));
        }
        return total;
    }

    private static BigInteger doubledWeightedX(List<LoadUnit> units, long tareWeightTicks, long tareDoubledCenterX) {
        BigInteger weighted = big(tareWeightTicks).multiply(big(tareDoubledCenterX));
        for (LoadUnit unit : units) {
            long doubledCenterX = Math.addExact(Math.multiplyExact(2, unit.box().origin().x()),
                    unit.box().dimensions().length().ticks());
            weighted = weighted.add(big(unit.weightTicks()).multiply(big(doubledCenterX)));
        }
        return weighted;
    }

    /** Floor division by a strictly positive denominator. */
    private static BigInteger floorDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && quotientAndRemainder[1].signum() != 0
                ? quotientAndRemainder[0].subtract(BigInteger.ONE)
                : quotientAndRemainder[0];
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        return floorDiv(numerator.negate(), denominator).negate();
    }

    /** The reference engine's {@code (int)} of an integer string clamps rather than wraps. */
    private static long saturatingLong(BigInteger value) {
        return value.max(LONG_MIN).min(LONG_MAX).longValue();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
