package com.packvium.sequence;

import com.packvium.constraint.ContactGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Unloading dependency graph: which placements must be removed before others,
 * replaying from the full, final scene.
 *
 * <p>{@code dependsOn[i]} is {@code i}'s children: whatever rests on {@code i} sits strictly
 * above it, so following edges strictly increases height and a finite set cannot cycle back on
 * itself -- checked, not assumed, by {@link #isAcyclic}.
 *
 * <p>{@link #safeRemovalOrder} runs a real simulation, not a topological sort of a static
 * graph. Its error path is unreachable for solver output when every direction is allowed: an
 * item with nothing above it always has a clear {@code +z} path to the ceiling, so the topmost
 * items are always removable, and induction completes the order. A restricted direction set
 * is the only way a genuine cycle can arise.
 */
public final class UnloadingDependencyGraph {
    public static final List<String> ALL_DIRECTIONS = SequenceGeometry.ALL_DIRECTIONS;

    private final Map<Integer, Set<Integer>> dependsOn;

    /** Public so a test can build a synthetic (non-geometric) graph to prove the cycle check. */
    public UnloadingDependencyGraph(Map<Integer, Set<Integer>> dependsOn) {
        this.dependsOn = DependencyMaps.frozen(dependsOn);
    }

    public static UnloadingDependencyGraph build(List<AxisAlignedBox> boxes) {
        ContactGraph contacts = new ContactGraph(boxes);
        Map<Integer, Set<Integer>> dependsOn = new LinkedHashMap<>();
        for (int index = 0; index < boxes.size(); index++) {
            dependsOn.put(index, new LinkedHashSet<>(contacts.children(index)));
        }
        return new UnloadingDependencyGraph(dependsOn);
    }

    public Map<Integer, Set<Integer>> dependsOn() {
        return dependsOn;
    }

    public boolean isAcyclic() {
        return SequenceGeometry.isAcyclic(dependsOn);
    }

    public static List<Integer> safeRemovalOrder(List<AxisAlignedBox> boxes, Dimensions container) {
        return safeRemovalOrder(boxes, container, ALL_DIRECTIONS);
    }

    /**
     * A concrete order placements could be removed in, starting from the full scene, without
     * ever moving a still-present placement out of the way first. O(n^3 * d): n steps, each
     * testing up to n candidates against n others in d directions.
     */
    public static List<Integer> safeRemovalOrder(List<AxisAlignedBox> boxes, Dimensions container,
            List<String> directions) {
        SequenceGeometry.validated(directions);
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = DependencyMaps.allIndexes(boxes.size());
        List<Integer> order = new ArrayList<>();
        while (!present.isEmpty()) {
            Optional<Integer> chosen = lowestRemovable(present, present, dependsOn, boxes, container, directions);
            if (chosen.isEmpty()) {
                throw new SequenceError(new ArrayList<>(present));
            }
            order.add(chosen.get());
            present.remove(chosen.get());
        }
        replayRemovalOrder(boxes, container, order, directions);
        return List.copyOf(order);
    }

    /**
     * Deterministic: the lowest index among this step's removable candidates, not arbitrary
     * iteration order.
     */
    private static Optional<Integer> lowestRemovable(Set<Integer> candidates, Set<Integer> remaining,
            Map<Integer, Set<Integer>> dependsOn, List<AxisAlignedBox> boxes, Dimensions container,
            List<String> directions) {
        TreeSet<Integer> removable = new TreeSet<>();
        for (int index : candidates) {
            if (!Collections.disjoint(dependsOn.getOrDefault(index, Set.of()), remaining)) {
                continue;
            }
            if (SequenceGeometry.clearDirection(index, boxes.get(index), boxes, remaining, container, directions)
                    .isPresent()) {
                removable.add(index);
            }
        }
        return removable.isEmpty() ? Optional.empty() : Optional.of(removable.first());
    }

    public static List<Integer> safeRouteRemovalOrder(List<AxisAlignedBox> boxes, List<Optional<Long>> stops,
            Dimensions container) {
        return safeRouteRemovalOrder(boxes, stops, container, ALL_DIRECTIONS);
    }

    /**
     * A concrete unloading order that fully empties each stop, in ascending stop order, before
     * the next one starts. An empty stop means the placement is not on the route: it
     * is never scheduled for removal and stays present throughout, but still counts as a
     * potential blocker. A request with every stop empty schedules nothing and returns [].
     */
    public static List<Integer> safeRouteRemovalOrder(List<AxisAlignedBox> boxes, List<Optional<Long>> stops,
            Dimensions container, List<String> directions) {
        SequenceGeometry.validated(directions);
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> remaining = DependencyMaps.allIndexes(boxes.size());
        List<Integer> order = new ArrayList<>();
        TreeSet<Long> sortedStops = new TreeSet<>();
        for (Optional<Long> stop : stops) {
            stop.ifPresent(sortedStops::add);
        }
        for (long stop : sortedStops) {
            Set<Integer> due = new LinkedHashSet<>();
            for (int index : remaining) {
                if (stopAt(stops, index).filter(value -> value == stop).isPresent()) {
                    due.add(index);
                }
            }
            while (!due.isEmpty()) {
                Optional<Integer> chosen = lowestRemovable(due, remaining, dependsOn, boxes, container, directions);
                if (chosen.isEmpty()) {
                    throw new RouteSequenceError(stop, new ArrayList<>(due));
                }
                order.add(chosen.get());
                remaining.remove(chosen.get());
                due.remove(chosen.get());
            }
        }
        return List.copyOf(order);
    }

    /** A missing trailing entry reads as "not on the route", as an absent array key does upstream. */
    private static Optional<Long> stopAt(List<Optional<Long>> stops, int index) {
        return index < stops.size() ? stops.get(index) : Optional.empty();
    }

    public static List<SequenceStep> safeRemovalOrderWithEvidence(List<AxisAlignedBox> boxes, Dimensions container) {
        return safeRemovalOrderWithEvidence(boxes, container, ALL_DIRECTIONS);
    }

    /** {@link #safeRemovalOrder}, with each step's support dependency and used direction attached. */
    public static List<SequenceStep> safeRemovalOrderWithEvidence(List<AxisAlignedBox> boxes, Dimensions container,
            List<String> directions) {
        List<Integer> order = safeRemovalOrder(boxes, container, directions);
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = DependencyMaps.allIndexes(boxes.size());
        List<SequenceStep> steps = new ArrayList<>();
        for (int index : order) {
            String direction = SequenceGeometry.clearDirection(index, boxes.get(index), boxes, present, container,
                    directions).orElseThrow();
            steps.add(new SequenceStep(index, direction, dependsOn.getOrDefault(index, Set.of())));
            present.remove(index);
        }
        return List.copyOf(steps);
    }

    public static List<Reachability> placementReachability(List<AxisAlignedBox> boxes, Dimensions container) {
        return placementReachability(boxes, container, Collections.nCopies(boxes.size(), Optional.empty()),
                ALL_DIRECTIONS);
    }

    public static List<Reachability> placementReachability(List<AxisAlignedBox> boxes, Dimensions container,
            List<Optional<Long>> stops) {
        return placementReachability(boxes, container, stops, ALL_DIRECTIONS);
    }

    /**
     * Per-placement reachability for the full scene as it stands, one entry per box.
     *
     * <p>Reuses this class's own dependency graph and the same accessibility sweep
     * {@link #safeRemovalOrder} is built from -- no second notion of "blocked". When stops are
     * present, a placement due later than the earliest outstanding stop is blocked by every
     * present placement due earlier. O(n^2 * d) for n placements and d directions.
     */
    public static List<Reachability> placementReachability(List<AxisAlignedBox> boxes, Dimensions container,
            List<Optional<Long>> stops, List<String> directions) {
        SequenceGeometry.validated(directions);
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = DependencyMaps.allIndexes(boxes.size());
        if (stops.size() != boxes.size()) {
            throw new IllegalArgumentException("stops must contain exactly one entry per placement");
        }
        Optional<Long> earliestDueStop = stops.stream().flatMap(Optional::stream).min(Long::compare);
        List<Reachability> results = new ArrayList<>();
        for (int index = 0; index < boxes.size(); index++) {
            AxisAlignedBox box = boxes.get(index);
            Set<Integer> blockedBySupport = new LinkedHashSet<>();
            for (int dependency : dependsOn.getOrDefault(index, Set.of())) {
                if (present.contains(dependency)) {
                    blockedBySupport.add(dependency);
                }
            }
            Set<Integer> blockedByRoute = new LinkedHashSet<>();
            Optional<Long> stop = stops.get(index);
            if (stop.isPresent() && earliestDueStop.isPresent() && !stop.get().equals(earliestDueStop.get())) {
                for (int other : present) {
                    if (other != index && stops.get(other).isPresent() && stops.get(other).get() < stop.get()) {
                        blockedByRoute.add(other);
                    }
                }
            }
            Optional<String> clear = SequenceGeometry.clearDirection(index, box, boxes, present, container,
                    directions);
            Set<Integer> blockedByNeighbors = new TreeSet<>();
            if (clear.isEmpty() && !directions.isEmpty()) {
                for (String direction : directions) {
                    blockedByNeighbors.addAll(SequenceGeometry.blockingIndices(index, box, boxes, present, container,
                            direction));
                }
            }
            boolean reachable = blockedBySupport.isEmpty() && blockedByRoute.isEmpty() && clear.isPresent();
            results.add(new Reachability(index, reachable, blockedBySupport, blockedByNeighbors, blockedByRoute));
        }
        return List.copyOf(results);
    }

    public static void replayRemovalOrder(List<AxisAlignedBox> boxes, Dimensions container, List<Integer> order) {
        replayRemovalOrder(boxes, container, order, ALL_DIRECTIONS);
    }

    /**
     * Independently replay an unloading order (full scene to empty) and throw at the first step
     * that is not feasible given only what remains present. It reuses the same
     * geometric primitives as {@link #safeRemovalOrder} but none of its search or tie-break
     * logic, so it can catch a generator bug or a wrong externally-supplied order. O(n^2 * d).
     */
    public static void replayRemovalOrder(List<AxisAlignedBox> boxes, Dimensions container, List<Integer> order,
            List<String> directions) {
        SequenceGeometry.validated(directions);
        DependencyMaps.requirePermutation(order, boxes.size());
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = DependencyMaps.allIndexes(boxes.size());
        for (int step = 0; step < order.size(); step++) {
            int index = order.get(step);
            SequenceGeometry.validateBoxAtStep(index, step, boxes, present, container);
            for (int dependency : dependsOn.getOrDefault(index, Set.of())) {
                if (present.contains(dependency)) {
                    throw new SequenceReplayError(index, step, "something still resting on it has not been removed yet");
                }
            }
            if (SequenceGeometry.clearDirection(index, boxes.get(index), boxes, present, container, directions)
                    .isEmpty()) {
                throw new SequenceReplayError(index, step, "no allowed direction is clear of the remaining placements");
            }
            present.remove(index);
        }
    }
}
