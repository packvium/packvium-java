package com.packvium.algorithm;

import com.packvium.constraint.VolumeReserve;
import com.packvium.domain.Container;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.ShapeType;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lower bounds on the objective vector, and the gap against an incumbent.
 *
 * <p>The mathematics is fixed by docs/OPTIMALITY-CERTIFICATES.md. This is a port of the Python
 * implementation and is held to byte-identical results on the shared corpus, which is
 * what {@code conformance/scene/objective-bounds.json} exists to prove: Python writes the
 * vectors, this engine recomputes them from the same fixtures, and any divergence fails.
 *
 * <p>Arithmetic. A container's volume in ticks passes a 64-bit integer well before it reaches a
 * realistic size -- a one-metre cube is 4.1e21 cubic ticks -- so every volume, weight sum,
 * capacity and selected cost here is a {@link BigInteger}. Counts and the two
 * parts-per-million keys return to {@code long} only after the common portable-result ceiling
 * has been checked.
 *
 * <p>Complexity. {@code O(n log n + c log c)} time and {@code O(n + c)} space for {@code n}
 * instances and {@code c} container types: one sort of the volumes, one of the weights, one of
 * the per-unit costs.
 *
 * <p>Package. {@code com.packvium.algorithm} is internal, deliberately: a lower bound is solver
 * machinery, recorded into {@code SearchStats} by its only production caller and serialized by
 * nothing, so it adds nothing to the frozen public API.
 */
public final class ObjectiveBounds {
    /** Parts per million, the scale keys 3 and 4 of the objective are carried at. */
    public static final long PPM = 1000000;

    /**
     * Every sum in the bound path must stay below this.
     *
     * <p>Declared rather than inherited: PHP's native integers silently become doubles on
     * overflow, JavaScript's stop being exact past 2^53, Python's are unbounded and Rust's
     * {@code i128} wraps. If each refused at its own limit the engines would disagree about
     * which requests are answerable. Keys 3 and 4 multiply a summed volume by {@code PPM}, so
     * {@code 10^30 * 10^6} sits about 170-fold inside {@code i128}.
     */
    public static final BigInteger MAX_BOUND_SUM = new BigInteger("1000000000000000000000000000000");

    /** Largest result integer every binding can carry without changing its value. */
    public static final BigInteger MAX_BOUND_VALUE = BigInteger.valueOf(9007199254740991L);

    private static final BigInteger BIG_PPM = BigInteger.valueOf(PPM);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** One instance as the formulas consume it. */
    public record NormalisedInstance(BigInteger volume, long weight, boolean shrinks) {
    }

    /** One container type as the formulas consume it. */
    public record NormalisedContainer(
            BigInteger usable,
            BigInteger inner,
            long baseArea,
            long height,
            Optional<Long> payload,
            Optional<Long> maxItems,
            Optional<Long> quantity,
            long costMinor) {
    }

    private ObjectiveBounds() {
    }

    /**
     * Refuse a sum past the declared ceiling instead of carrying it further.
     *
     * <p>A structured refusal rather than a number: the alternative is the failure Baldacci et
     * al. document for floating-point bin-packing solvers, a bound that is quietly wrong and
     * carries no signal that it is.
     */
    private static BigInteger guard(BigInteger total, String quantity) {
        if (total.compareTo(MAX_BOUND_SUM) > 0) {
            throw new BoundOverflowException(
                    quantity + " sums past the " + MAX_BOUND_SUM
                            + " ceiling the bound path declares; refusing rather than returning a number"
                            + " no engine can agree on");
        }
        return total;
    }

    /** Refuse a result before PHP or JavaScript can round it on a binding boundary. */
    private static long guardOutput(BigInteger value, String quantity) {
        if (value.compareTo(MAX_BOUND_VALUE) > 0) {
            throw new BoundOverflowException(
                    quantity + " bound is " + value + ", above the " + MAX_BOUND_VALUE
                            + " exact portable result ceiling");
        }
        return value.longValue();
    }

    /**
     * Every bound for one request, in the default objective's key order.
     *
     * @param instances quantity-expanded, exactly as the solver sees them
     * @param containers the types the request permits
     */
    public static long[] compute(List<ItemInstance> instances, List<Container> containers) {
        List<NormalisedInstance> normalisedInstances = new ArrayList<>(instances.size());
        for (ItemInstance instance : instances) {
            normalisedInstances.add(new NormalisedInstance(
                    instance.item().dimensions().volume(),
                    instance.item().weight().ticks(),
                    occupiesLessThanItsBox(instance.item())));
        }
        List<NormalisedContainer> normalisedContainers = new ArrayList<>(containers.size());
        for (Container container : containers) {
            normalisedContainers.add(new NormalisedContainer(
                    VolumeReserve.usable(container),
                    container.innerDimensions().volume(),
                    container.innerDimensions().baseAreaTicks(),
                    container.innerDimensions().height().ticks(),
                    container.maxPayload().map(Weight::ticks),
                    container.maxItems(),
                    container.quantity(),
                    container.costMinor()));
        }
        return fromNormalised(normalisedInstances, normalisedContainers);
    }

    /**
     * The same bounds, from the numbers the formulas actually consume.
     *
     * <p>This is the shape {@code conformance/scene/objective-bounds.json} records, so that a
     * port can be held to Python's vectors without first reimplementing a request parser. It
     * takes {@code shrinks} as given rather than deriving it: whether this engine decides that
     * flag correctly for a {@code convex_hull} is asserted separately, in this engine's own
     * tests, so a port cannot pass the arithmetic check by copying a flag it never computes.
     */
    public static long[] fromNormalised(List<NormalisedInstance> instances, List<NormalisedContainer> containers) {
        List<BigInteger> volumes = new ArrayList<>(instances.size());
        List<Long> weights = new ArrayList<>(instances.size());
        boolean shrinks = false;
        for (NormalisedInstance instance : instances) {
            volumes.add(instance.volume());
            weights.add(instance.weight());
            shrinks = shrinks || instance.shrinks();
        }
        Collections.sort(volumes);
        Collections.sort(weights);
        int count = instances.size();

        // The a-priori check, once, on the way in. Every later product is bounded by these
        // totals times `PPM`, so guarding them here is what makes the rest safe by derivation
        // rather than by hoping each step stays small.
        guard(sum(volumes), "instance volume");
        guard(sumLongs(weights), "instance weight");
        BigInteger capacity = BigInteger.ZERO;
        BigInteger openingCost = BigInteger.ZERO;
        for (NormalisedContainer container : containers) {
            BigInteger quantity = BigInteger.valueOf(container.quantity().orElse(1L));
            capacity = capacity.add(container.usable().multiply(quantity));
            openingCost = openingCost.add(BigInteger.valueOf(container.costMinor()).multiply(quantity));
        }
        guard(capacity, "container capacity");
        guard(openingCost, "opening cost");

        long unpacked = unpackedBound(volumes, weights, shrinks, count, containers);
        long placed = count - unpacked;
        long opened = containerBound(volumes, weights, shrinks, placed, containers);
        BigInteger cost = costBound(containers, opened);
        BigInteger unused = unusedVolumeBound(volumes, shrinks, placed, containers, opened);
        BigInteger height = stackHeightBound(volumes, shrinks, placed, containers, opened);
        return new long[] {
            guardOutput(BigInteger.valueOf(unpacked), "unpacked count"),
            guardOutput(BigInteger.valueOf(opened), "container count"),
            guardOutput(cost, "opening cost"),
            guardOutput(unused, "unused volume"),
            guardOutput(height, "stack height"),
        };
    }

    /**
     * Can this item take up less room than its declared dimensions?
     *
     * <p>Three ways, and each breaks the same argument -- that nominal volumes sum to something
     * a solution must carry. A nested item sinks into the one below it; a {@code convex_hull}
     * occupies its hull and leaves the rest of its bounding box free; a {@code compressible}
     * item gives up height under load. Asking the question once, here, is what stops a future
     * fourth shape from reintroducing the same unsoundness silently.
     */
    static boolean occupiesLessThanItsBox(Item item) {
        if (item.nestingHeight().isPresent()) {
            return true;
        }
        return item.shapeType() == ShapeType.CONVEX_HULL || item.shapeType() == ShapeType.COMPRESSIBLE;
    }

    /** {@code L0}: remove the geometry entirely and ask what the declared resources alone forbid. */
    private static long unpackedBound(List<BigInteger> volumes, List<Long> weights, boolean shrinks, int count,
            List<NormalisedContainer> containers) {
        long placeable = count;
        if (!shrinks) {
            placeable = Math.min(placeable, fit(volumes, volumeCapacity(containers)));
        }
        Optional<BigInteger> payloadCapacity = nativeCapacity(containers, NormalisedContainer::payload);
        placeable = Math.min(placeable, fit(longsToBig(weights), payloadCapacity));
        Optional<BigInteger> slotCapacity = nativeCapacity(containers, NormalisedContainer::maxItems);
        if (slotCapacity.isPresent()) {
            BigInteger slots = slotCapacity.get();
            placeable = Math.min(placeable,
                    slots.compareTo(BigInteger.valueOf(count)) >= 0 ? count : slots.longValueExact());
        }
        return count - placeable;
    }

    /**
     * {@code L1}: grant every container the largest capacity available, which can only
     * understate how many are needed.
     */
    private static long containerBound(List<BigInteger> volumes, List<Long> weights, boolean shrinks, long placed,
            List<NormalisedContainer> containers) {
        if (placed <= 0 || containers.isEmpty()) {
            return 0;
        }
        long bound = 1;
        int taken = (int) placed;
        if (!shrinks) {
            BigInteger largest = BigInteger.ZERO;
            for (NormalisedContainer container : containers) {
                largest = largest.max(container.usable());
            }
            if (largest.signum() > 0) {
                bound = Math.max(bound, saturate(ceilDiv(sum(volumes.subList(0, taken)), largest)));
            }
        }
        Optional<Long> largestPayload = finiteMax(containers, NormalisedContainer::payload);
        if (largestPayload.isPresent() && largestPayload.get() > 0) {
            BigInteger totalWeight = sumLongs(weights.subList(0, taken));
            bound = Math.max(bound, saturate(ceilDiv(totalWeight, BigInteger.valueOf(largestPayload.get()))));
        }
        Optional<Long> largestSlots = finiteMax(containers, NormalisedContainer::maxItems);
        if (largestSlots.isPresent() && largestSlots.get() > 0) {
            // `(placed - 1) / slots + 1` is the ceiling without the `placed + slots - 1`
            // intermediate, which overflows for a slot limit near the top of the range.
            bound = Math.max(bound, (placed - 1) / largestSlots.get() + 1);
        }
        return bound;
    }

    /**
     * {@code L2}: at least {@code L1} containers open, each costing at least the cheapest the
     * inventory still holds.
     *
     * <p>The reference engines materialise {@code min(quantity, opened)} copies of each cost and
     * sort them; taking the cheapest types first with their multiplicity selects the same
     * multiset in {@code O(c log c)} rather than {@code O(c * opened)}.
     */
    private static BigInteger costBound(List<NormalisedContainer> containers, long opened) {
        if (opened <= 0) {
            return BigInteger.ZERO;
        }
        List<NormalisedContainer> cheapestFirst = new ArrayList<>(containers);
        cheapestFirst.sort(Comparator.comparingLong(NormalisedContainer::costMinor));
        long remaining = opened;
        BigInteger selected = BigInteger.ZERO;
        for (NormalisedContainer container : cheapestFirst) {
            if (remaining == 0) {
                break;
            }
            long repeat = container.quantity().map(quantity -> Math.min(quantity, opened)).orElse(opened);
            long take = Math.min(repeat, remaining);
            selected = selected.add(BigInteger.valueOf(container.costMinor()).multiply(BigInteger.valueOf(take)));
            remaining -= take;
        }
        guard(selected, "opening cost");
        return selected;
    }

    /**
     * {@code L3}: the fill is largest when every container is the smallest available and holds
     * the greatest volume that could be placed at all.
     */
    private static BigInteger unusedVolumeBound(List<BigInteger> volumes, boolean shrinks, long placed,
            List<NormalisedContainer> containers, long opened) {
        if (shrinks || opened <= 0 || containers.isEmpty()) {
            return BigInteger.ZERO;
        }
        BigInteger smallest = null;
        for (NormalisedContainer container : containers) {
            if (smallest == null || container.inner().compareTo(smallest) < 0) {
                smallest = container.inner();
            }
        }
        if (smallest.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger largestPlaced = placed > 0
                ? sum(volumes.subList(volumes.size() - (int) placed, volumes.size()))
                : BigInteger.ZERO;
        BigInteger filled = ceilDiv(largestPlaced.multiply(BIG_PPM), smallest);
        BigInteger bigOpened = BigInteger.valueOf(opened);
        BigInteger maximum = bigOpened.multiply(BIG_PPM).subtract(bigOpened.subtract(BigInteger.ONE));
        if (filled.compareTo(maximum) >= 0) {
            return BigInteger.ZERO;
        }
        return maximum.subtract(filled);
    }

    /**
     * {@code L4}: the volume that must be placed has to stand at least as tall as itself spread
     * across the widest floor available, in the tallest container available.
     *
     * <p>The {@code L1 - 1} correction is the exact worst-case difference between a sum of
     * floors and the floor of a sum, not a cautionary fudge: the objective floors each
     * container's ratio before summing.
     */
    private static BigInteger stackHeightBound(List<BigInteger> volumes, boolean shrinks, long placed,
            List<NormalisedContainer> containers, long opened) {
        if (shrinks || opened <= 0 || containers.isEmpty()) {
            return BigInteger.ZERO;
        }
        long widest = Long.MIN_VALUE;
        long tallest = Long.MIN_VALUE;
        for (NormalisedContainer container : containers) {
            widest = Math.max(widest, container.baseArea());
            tallest = Math.max(tallest, container.height());
        }
        if (widest <= 0 || tallest <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger required = BigInteger.ZERO;
        if (placed > 0) {
            BigInteger total = sum(volumes.subList(0, (int) placed));
            if (total.signum() > 0) {
                required = ceilDiv(total, BigInteger.valueOf(widest));
            }
        }
        BigInteger ratio = required.multiply(BIG_PPM).divide(BigInteger.valueOf(tallest));
        BigInteger correction = BigInteger.valueOf(opened - 1);
        if (ratio.compareTo(correction) <= 0) {
            return BigInteger.ZERO;
        }
        return ratio.subtract(correction);
    }

    /**
     * {@code Sum of usable * quantity}, or empty for an unbounded total.
     *
     * <p>A container with no usable volume adds nothing however many of it there are, which is
     * why an unlimited quantity only makes the total unbounded when the type actually holds
     * something.
     */
    private static Optional<BigInteger> volumeCapacity(List<NormalisedContainer> containers) {
        BigInteger total = BigInteger.ZERO;
        for (NormalisedContainer container : containers) {
            if (container.quantity().isEmpty()) {
                if (container.usable().signum() > 0) {
                    return Optional.empty();
                }
                continue;
            }
            total = total.add(container.usable().multiply(BigInteger.valueOf(container.quantity().get())));
        }
        return Optional.of(total);
    }

    /** {@code Sum of limit * quantity}, or empty when any limit or inventory is undeclared. */
    private static Optional<BigInteger> nativeCapacity(List<NormalisedContainer> containers,
            Function<NormalisedContainer, Optional<Long>> limit) {
        BigInteger total = BigInteger.ZERO;
        for (NormalisedContainer container : containers) {
            Optional<Long> value = limit.apply(container);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            if (container.quantity().isEmpty()) {
                if (value.get() > 0) {
                    return Optional.empty();
                }
                continue;
            }
            total = total.add(BigInteger.valueOf(value.get()).multiply(BigInteger.valueOf(container.quantity().get())));
            guard(total, "container capacity");
        }
        return Optional.of(total);
    }

    /** The largest {@code n} such that the {@code n} smallest values sum to at most the capacity. */
    private static long fit(List<BigInteger> ascending, Optional<BigInteger> capacity) {
        if (capacity.isEmpty()) {
            return ascending.size();
        }
        BigInteger used = BigInteger.ZERO;
        for (int taken = 0; taken < ascending.size(); taken++) {
            used = used.add(ascending.get(taken));
            if (used.compareTo(capacity.get()) > 0) {
                return taken;
            }
        }
        return ascending.size();
    }

    /**
     * The largest declared limit, or empty if any type declares none.
     *
     * <p>One unlimited type makes the maximum unbounded and every term conditioned on it
     * vacuous, which is why this collapses to empty rather than ignoring the gap.
     */
    private static Optional<Long> finiteMax(List<NormalisedContainer> containers,
            Function<NormalisedContainer, Optional<Long>> limit) {
        Long best = null;
        for (NormalisedContainer container : containers) {
            Optional<Long> value = limit.apply(container);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            best = best == null ? value.get() : Math.max(best, value.get());
        }
        return Optional.ofNullable(best);
    }

    /** Ceiling of a non-negative quotient; zero stays zero. */
    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() == 0) {
            return BigInteger.ZERO;
        }
        return numerator.subtract(BigInteger.ONE).add(denominator).divide(denominator);
    }

    /**
     * PHP's {@code (int)} on an over-wide decimal string saturates at the native maximum, which
     * the output guard then refuses; saturating here keeps that refusal and its message.
     */
    private static long saturate(BigInteger value) {
        return value.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : value.longValue();
    }

    private static BigInteger sum(List<BigInteger> values) {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger value : values) {
            total = total.add(value);
        }
        return total;
    }

    private static BigInteger sumLongs(List<Long> values) {
        BigInteger total = BigInteger.ZERO;
        long running = 0;
        for (long value : values) {
            // Native while exact; the switch happens on the addend that would have wrapped.
            long next = running + value;
            if (((running ^ next) & (value ^ next)) < 0) {
                total = total.add(BigInteger.valueOf(running));
                next = value;
            }
            running = next;
        }
        return total.add(BigInteger.valueOf(running));
    }

    private static List<BigInteger> longsToBig(List<Long> values) {
        List<BigInteger> big = new ArrayList<>(values.size());
        for (long value : values) {
            big.add(BigInteger.valueOf(value));
        }
        return big;
    }
}
