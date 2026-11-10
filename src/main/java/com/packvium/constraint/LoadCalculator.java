package com.packvium.constraint;

import com.packvium.constraint.internal.LoadAnalysis;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.Item;
import com.packvium.domain.Placement;
import com.packvium.unit.Length;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whole-scene load checks, each building its own support graph. O(n log n + E) per call for
 * the loads; the ancestor-set queries are O(n^2) worst case.
 */
public final class LoadCalculator {
    /**
     * One square metre in length ticks squared, the reference area a stack density limit is
     * expressed per, derived from the single tick scale so it can never drift from it.
     */
    public static final long SQUARE_METRE_TICKS = (1000 * Length.TICKS_PER_MM) * (1000 * Length.TICKS_PER_MM);

    private LoadCalculator() {
    }

    public static List<LoadUnit> units(List<Placement> placements) {
        List<LoadUnit> units = new ArrayList<>(placements.size() + 1);
        for (Placement placement : placements) {
            Item item = placement.instance().item();
            units.add(new LoadUnit(placement.envelopeBox(), placement.instance().weight().ticks(),
                    item.maxTopLoad().map(weight -> weight.ticks()), item.maxStackedItems(),
                    placement.instance().id(),
                    item.nestingHeight().isPresent() ? Optional.of(item.id()) : Optional.empty(),
                    item.nestingHeight().map(Length::ticks), item.compressionRatioPpm(),
                    item.maxCompressionPressureKpa()));
        }
        return units;
    }

    public static List<LoadUnit> units(List<Placement> placements, LoadUnit extra) {
        List<LoadUnit> units = units(placements);
        units.add(extra);
        return units;
    }

    private static LoadAnalysis analysis(List<LoadUnit> units) {
        return new LoadAnalysis(units, new LoadSupportGraph(units));
    }

    /**
     * Weight borne by each unit, propagated down the whole stack. Every box pushes its own
     * weight plus everything already resting on it onto its direct face or nesting supports,
     * split by contact area. The integer remainder goes to the last supporter so the
     * distributed total is conserved exactly.
     */
    public static long[] topLoads(List<LoadUnit> units) {
        return analysis(units).topLoads();
    }

    /** First unit whose bearing limit is exceeded. */
    public static Optional<Violation> overloaded(List<LoadUnit> units) {
        return analysis(units).overloaded();
    }

    /**
     * First compressible unit carrying more pressure than it declared it can take.
     * {@code max_top_load} is a mass the box below must bear, {@code max_compression_pressure_kpa}
     * a pressure the item itself must survive; an item can pass one and fail the other.
     */
    public static Optional<Violation> crushed(List<LoadUnit> units) {
        return analysis(units).crushed();
    }

    /**
     * Everything resting anywhere above each unit, following the support graph upward. An item
     * stacked through two levels of intermediaries appears in the set of every level beneath it.
     */
    public static List<Set<Integer>> restingAbove(List<LoadUnit> units) {
        return analysis(units).restingAbove();
    }

    /** Items resting anywhere above each unit. A count, not a weight. */
    public static long[] stackedCounts(List<LoadUnit> units) {
        return analysis(units).stackedCounts();
    }

    /**
     * First item due at an earlier stop with a later-stop item resting above it.
     *
     * <p>Vertical blocking is the half of route order a placement decision can see by itself:
     * an item bound for a later stop, resting anywhere above an earlier-stop one, has to come
     * off first. Whether an unblocked box can also slide out of some wall is a whole-container
     * question the post-validator answers; this is the necessary condition, not the whole of
     * it. An absent stop is {@link Double#POSITIVE_INFINITY}: it rides the whole route, never
     * removed, always a blocker.
     */
    public static Optional<Violation> routeOrderViolated(List<LoadUnit> units, List<Double> stops) {
        if (stops.stream().allMatch(stop -> stop == Double.POSITIVE_INFINITY)) {
            return Optional.empty();
        }
        List<Set<Integer>> above = restingAbove(units);
        for (int index = 0; index < above.size(); index++) {
            for (int upper : above.get(index)) {
                if (stops.get(upper) > stops.get(index)) {
                    return Optional.of(new Violation("unloading_order_violation",
                            units.get(index).label() + " blocked by " + units.get(upper).label()));
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<Violation> stackLimitExceeded(List<LoadUnit> units) {
        return analysis(units).stackLimitExceeded();
    }

    /**
     * First unit whose cumulative load crushes its own footprint. {@code maxDensityTicks} is
     * weight ticks allowed per square metre of a unit's own base area, checked at every level of
     * the stack: a wide item narrowing to a small waist part-way up is exactly the case a flat
     * per-item max_top_load cannot express. Compared exactly, not divided.
     */
    public static Optional<Violation> stackDensityExceeded(List<LoadUnit> units, Optional<Long> maxDensityTicks) {
        return analysis(units).stackDensityExceeded(maxDensityTicks);
    }
}
