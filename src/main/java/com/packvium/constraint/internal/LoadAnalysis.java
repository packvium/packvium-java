package com.packvium.constraint.internal;

import com.packvium.constraint.ContactEdge;
import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.LoadUnit;
import com.packvium.constraint.Violation;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Compression;
import com.packvium.support.Arithmetic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Per-layout load analysis over an already constructed support graph.
 *
 * <p>LoadCalculator remains the allocation-owning facade. Search code that already owns an
 * incremental LoadSupportGraph uses this session instead, so the graph and its derived
 * load/ancestor vectors are each computed at most once. Building the load vector is
 * O(n + E) over the graph's descending order, which costs O(n log n) for a fresh graph and
 * O(n) for one extended by a unit; the ancestor sets are O(n^2) worst case, as the
 * PHP's are. Query methods are O(n) and never rebuild contact geometry.
 */
public final class LoadAnalysis {
    private final List<LoadUnit> units;
    private final LoadSupportGraph graph;
    private long[] topLoads;
    private List<Set<Integer>> restingAbove;

    public LoadAnalysis(List<LoadUnit> units, LoadSupportGraph graph) {
        this.units = List.copyOf(units);
        this.graph = graph;
    }

    /**
     * Weight borne by each unit, propagated down the whole stack. Every box pushes its own
     * weight plus everything resting on it onto its direct supports, split by contact area;
     * the integer remainder goes to the last supporter so the total is conserved exactly.
     */
    public long[] topLoads() {
        return loads().clone();
    }

    private long[] loads() {
        if (topLoads == null) {
            topLoads = computeTopLoads();
        }
        return topLoads;
    }

    private long[] computeTopLoads() {
        int count = units.size();
        long[] loads = new long[count];
        for (int upperIndex : graph.descending()) {
            List<ContactEdge> supports = graph.supporters(upperIndex);
            long totalArea = 0;
            for (ContactEdge edge : supports) {
                totalArea = Math.addExact(totalArea, edge.area());
            }
            if (totalArea == 0) {
                continue;
            }
            long downward = Math.addExact(units.get(upperIndex).weightTicks(), loads[upperIndex]);
            long assigned = 0;
            int last = supports.size() - 1;
            for (int position = 0; position < supports.size(); position++) {
                ContactEdge edge = supports.get(position);
                long share = position == last
                        ? downward - assigned
                        : Arithmetic.mulDiv(downward, edge.area(), totalArea);
                assigned = Math.addExact(assigned, share);
                loads[edge.index()] = Math.addExact(loads[edge.index()], share);
            }
        }
        return loads;
    }

    public Optional<Violation> overloaded() {
        if (units.stream().allMatch(unit -> unit.maxTopLoadTicks().isEmpty())) {
            return Optional.empty();
        }
        long[] loads = loads();
        for (int index = 0; index < units.size(); index++) {
            LoadUnit unit = units.get(index);
            if (unit.maxTopLoadTicks().isPresent() && loads[index] > unit.maxTopLoadTicks().get()) {
                return Optional.of(new Violation("top_load_exceeded", unit.label()));
            }
        }
        return Optional.empty();
    }

    /**
     * First compressible unit whose applied pressure exceeds its declared limit. A crush is a
     * hard boundary, not a worse score: the caller gets a refusal rather than a plan in which
     * something arrived flattened.
     */
    public Optional<Violation> crushed() {
        if (units.stream().allMatch(unit -> unit.maxCompressionPressureKpa().isEmpty())) {
            return Optional.empty();
        }
        long[] loads = loads();
        for (int index = 0; index < units.size(); index++) {
            LoadUnit unit = units.get(index);
            if (unit.maxCompressionPressureKpa().isEmpty()) {
                continue;
            }
            AxisAlignedBox box = unit.box();
            long footprint = Math.multiplyExact(box.x2() - box.origin().x(), box.y2() - box.origin().y());
            if (Compression.exceeds(Compression.pressure(loads[index], footprint),
                    unit.maxCompressionPressureKpa().get())) {
                return Optional.of(new Violation("crush_violation", unit.label()));
            }
        }
        return Optional.empty();
    }

    /**
     * Everything resting anywhere above each unit, following the support graph upward, each
     * set in first-reached order (children first, then what rests on each of them).
     *
     * <p>Walked iteratively in the load order the loads use: a child's top is above its
     * supporter's, so every child's set is complete before its supporter reads it, and no stack
     * depth -- a tall stack of sheets -- costs a call frame. O(sum of set sizes) after the load
     * order, which the graph sorts once in O(n log n) and caches.
     */
    public List<Set<Integer>> restingAbove() {
        if (restingAbove == null) {
            List<Set<Integer>> sets = new ArrayList<>(Collections.nCopies(units.size(), Set.of()));
            for (int index : graph.descending()) {
                Set<Integer> result = new LinkedHashSet<>();
                for (int child : graph.children(index)) {
                    result.add(child);
                    result.addAll(sets.get(child));
                }
                sets.set(index, Collections.unmodifiableSet(result));
            }
            restingAbove = List.copyOf(sets);
        }
        return restingAbove;
    }

    /**
     * First unit due at an earlier stop with a later-stop unit resting above it. {@code stops} is
     * parallel to the units; {@link Double#POSITIVE_INFINITY} rides the whole route. The uppers are
     * walked in ascending index order, not first-reached order: the detail names the first
     * violator, and every engine must name the same one. O(sum of set sizes times their log).
     */
    public Optional<Violation> routeOrderViolated(List<Double> stops) {
        List<Set<Integer>> above = restingAbove();
        for (int index = 0; index < above.size(); index++) {
            for (int upper : above.get(index).stream().sorted().toList()) {
                if (stops.get(upper) > stops.get(index)) {
                    return Optional.of(new Violation("unloading_order_violation",
                            units.get(index).label() + " blocked by " + units.get(upper).label()));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether any stop is a real one: a load that all rides the whole route has no order to violate. */
    public static boolean routed(List<Double> stops) {
        return stops.stream().anyMatch(stop -> stop != Double.POSITIVE_INFINITY);
    }

    public long[] stackedCounts() {
        List<Set<Integer>> above = restingAbove();
        long[] counts = new long[above.size()];
        for (int index = 0; index < counts.length; index++) {
            counts[index] = above.get(index).size();
        }
        return counts;
    }

    public Optional<Violation> stackLimitExceeded() {
        if (units.stream().allMatch(unit -> unit.maxStackedItems().isEmpty())) {
            return Optional.empty();
        }
        long[] counts = stackedCounts();
        for (int index = 0; index < units.size(); index++) {
            LoadUnit unit = units.get(index);
            if (unit.maxStackedItems().isPresent() && counts[index] > unit.maxStackedItems().get()) {
                return Optional.of(new Violation("stacked_item_limit_exceeded", unit.label()));
            }
        }
        return Optional.empty();
    }

    public Optional<Violation> stackDensityExceeded(Optional<Long> maxDensityTicks) {
        if (maxDensityTicks.isEmpty()) {
            return Optional.empty();
        }
        BigInteger squareMetre = BigInteger.valueOf(LoadCalculator.SQUARE_METRE_TICKS);
        BigInteger limit = BigInteger.valueOf(maxDensityTicks.get());
        long[] loads = loads();
        for (int index = 0; index < units.size(); index++) {
            LoadUnit unit = units.get(index);
            BigInteger bearing = BigInteger.valueOf(Math.addExact(unit.weightTicks(), loads[index]))
                    .multiply(squareMetre);
            BigInteger allowed = limit.multiply(BigInteger.valueOf(unit.box().dimensions().baseAreaTicks()));
            if (bearing.compareTo(allowed) > 0) {
                return Optional.of(new Violation("stack_density_exceeded", unit.label()));
            }
        }
        return Optional.empty();
    }
}
