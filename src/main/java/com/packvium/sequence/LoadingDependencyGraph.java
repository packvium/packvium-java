package com.packvium.sequence;

import com.packvium.constraint.ContactEdge;
import com.packvium.constraint.ContactGraph;
import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.LoadUnit;
import com.packvium.constraint.SupportConstraint;
import com.packvium.constraint.Violation;
import com.packvium.constraint.internal.DirectSupportView;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Placement;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loading dependency graph: which placements must already be present before others
 * can be loaded, replaying from an empty container.
 *
 * <p>{@code dependsOn[i]} is {@code i}'s supporters: whatever {@code i} rests on sits strictly
 * below it, so following edges strictly decreases height -- checked, not assumed, by
 * {@link #isAcyclic}.
 */
public final class LoadingDependencyGraph {
    public static final List<String> ALL_DIRECTIONS = SequenceGeometry.ALL_DIRECTIONS;

    private final Map<Integer, Set<Integer>> dependsOn;

    /** Public so a test can build a synthetic (non-geometric) graph to prove the cycle check. */
    public LoadingDependencyGraph(Map<Integer, Set<Integer>> dependsOn) {
        this.dependsOn = DependencyMaps.frozen(dependsOn);
    }

    public static LoadingDependencyGraph build(List<AxisAlignedBox> boxes) {
        ContactGraph contacts = new ContactGraph(boxes);
        Map<Integer, Set<Integer>> dependsOn = new LinkedHashMap<>();
        for (int index = 0; index < boxes.size(); index++) {
            Set<Integer> supporters = new LinkedHashSet<>();
            for (ContactEdge edge : contacts.supporters(index)) {
                supporters.add(edge.index());
            }
            dependsOn.put(index, supporters);
        }
        return new LoadingDependencyGraph(dependsOn);
    }

    public Map<Integer, Set<Integer>> dependsOn() {
        return dependsOn;
    }

    public boolean isAcyclic() {
        return SequenceGeometry.isAcyclic(dependsOn);
    }

    public static List<Integer> safeLoadingOrder(List<AxisAlignedBox> boxes, Dimensions container) {
        return safeLoadingOrder(boxes, container, ALL_DIRECTIONS);
    }

    /**
     * A concrete order placements could be loaded in, starting from an empty container.
     *
     * <p>Computed as the exact reverse of a valid unloading order, not a second forward search --
     * a proof, not a shortcut. Unloading only ever shrinks the present set, so its greedy choice
     * is trap-free; a naive forward loader is not, since an early greedy choice can permanently
     * block a later item's only clear direction. Unloading depends on a box's children, so
     * {@code s} supporting {@code r} forces {@code r} out before {@code s}, meaning {@code s}
     * precedes {@code r} in the reversal -- exactly this graph's supporters-first requirement.
     * The sweep is symmetric by construction. {@link #replayLoadingOrder} re-checks this
     * independently, so a mistake in the reasoning could not hide behind it.
     */
    public static List<Integer> safeLoadingOrder(List<AxisAlignedBox> boxes, Dimensions container,
            List<String> directions) {
        SequenceGeometry.validated(directions);
        List<Integer> order = new ArrayList<>(UnloadingDependencyGraph.safeRemovalOrder(boxes, container, directions));
        Collections.reverse(order);
        replayLoadingOrder(boxes, container, order, directions);
        return List.copyOf(order);
    }

    public static List<Integer> safeLoadingOrderForPlacements(List<Placement> placements, Container container) {
        return safeLoadingOrderForPlacements(placements, container, ALL_DIRECTIONS);
    }

    /**
     * Composed domain-level entry point: geometry, accessibility and every stacking business
     * rule are all replayed before an order is returned.
     */
    public static List<Integer> safeLoadingOrderForPlacements(List<Placement> placements, Container container,
            List<String> directions) {
        List<AxisAlignedBox> boxes = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            boxes.add(placement.envelopeBox());
        }
        List<Integer> order = safeLoadingOrder(boxes, container.innerDimensions(), directions);
        verifyLoadingPrefixBusinessRules(placements, order, container);
        return order;
    }

    public static List<SequenceStep> safeLoadingOrderWithEvidence(List<AxisAlignedBox> boxes, Dimensions container) {
        return safeLoadingOrderWithEvidence(boxes, container, ALL_DIRECTIONS);
    }

    /** {@link #safeLoadingOrder}, with each step's support dependency and used direction attached. */
    public static List<SequenceStep> safeLoadingOrderWithEvidence(List<AxisAlignedBox> boxes, Dimensions container,
            List<String> directions) {
        List<Integer> order = safeLoadingOrder(boxes, container, directions);
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = new LinkedHashSet<>();
        List<SequenceStep> steps = new ArrayList<>();
        for (int index : order) {
            String direction = SequenceGeometry.clearDirection(index, boxes.get(index), boxes, present, container,
                    directions).orElseThrow();
            steps.add(new SequenceStep(index, direction, dependsOn.getOrDefault(index, Set.of())));
            present.add(index);
        }
        return List.copyOf(steps);
    }

    public static void replayLoadingOrder(List<AxisAlignedBox> boxes, Dimensions container, List<Integer> order) {
        replayLoadingOrder(boxes, container, order, ALL_DIRECTIONS);
    }

    /**
     * Independently replay a loading order (empty container to full scene) and throw at the
     * first step that is not feasible given only what has been loaded. O(n^2 * d).
     */
    public static void replayLoadingOrder(List<AxisAlignedBox> boxes, Dimensions container, List<Integer> order,
            List<String> directions) {
        SequenceGeometry.validated(directions);
        DependencyMaps.requirePermutation(order, boxes.size());
        Map<Integer, Set<Integer>> dependsOn = build(boxes).dependsOn();
        Set<Integer> present = new LinkedHashSet<>();
        for (int step = 0; step < order.size(); step++) {
            int index = order.get(step);
            SequenceGeometry.validateBoxAtStep(index, step, boxes, present, container);
            for (int dependency : dependsOn.getOrDefault(index, Set.of())) {
                if (!present.contains(dependency)) {
                    throw new SequenceReplayError(index, step, "a supporter has not been loaded yet");
                }
            }
            if (SequenceGeometry.clearDirection(index, boxes.get(index), boxes, present, container, directions)
                    .isEmpty()) {
                throw new SequenceReplayError(index, step, "no allowed direction is clear of what has already been loaded");
            }
            present.add(index);
        }
    }

    /**
     * Reuse the exact constraint calculations proven for a finished scene against every loading
     * <em>prefix</em>, not only the final state, throwing at the first step whose prefix
     * violates a limit: identifying which addition first broke a limit is strictly more useful
     * than "the finished scene is invalid". O(n) prefixes, each O(n^2) worst case.
     *
     * <p>Ground contact rules are re-derived directly from the prefix, exactly as the support
     * constraint defines a supporting surface: at replay time the prefix is already the complete
     * set a spatial index would be asked for.
     */
    public static void verifyLoadingPrefixBusinessRules(List<Placement> placements, List<Integer> order,
            Container container) {
        DependencyMaps.requirePermutation(order, placements.size());
        Optional<Long> maxDensityTicks = container.maxStackDensity().map(Weight::ticks);
        List<Placement> present = new ArrayList<>();
        for (int step = 0; step < order.size(); step++) {
            int index = order.get(step);
            present.add(placements.get(index));
            Optional<Violation> problem = prefixProblem(present, maxDensityTicks);
            if (problem.isPresent()) {
                throw new SequenceReplayError(index, step, problem.get().code() + ": " + problem.get().detail());
            }
        }
    }

    private static Optional<Violation> prefixProblem(List<Placement> present, Optional<Long> maxDensityTicks) {
        List<LoadUnit> units = LoadCalculator.units(present);
        Optional<Violation> problem = LoadCalculator.overloaded(units)
                .or(() -> LoadCalculator.stackLimitExceeded(units))
                .or(() -> LoadCalculator.stackDensityExceeded(units, maxDensityTicks));
        if (problem.isPresent()) {
            return problem;
        }
        long[] counts = LoadCalculator.stackedCounts(units);
        for (int unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            if (!present.get(unitIndex).instance().item().stackable() && counts[unitIndex] > 0) {
                return Optional.of(new Violation("non_stackable_item_has_load", units.get(unitIndex).label()));
            }
        }
        return groundContactProblem(present);
    }

    private static Optional<Violation> groundContactProblem(List<Placement> present) {
        Placement candidate = present.get(present.size() - 1);
        Optional<String> rule = candidate.instance().item().groundContactRule();
        AxisAlignedBox candidateBox = candidate.envelopeBox();
        if (rule.isEmpty() || rule.get().equals("free") || candidateBox.origin().z() == 0) {
            return Optional.empty();
        }
        DirectSupportView support = LoadSupportGraph.candidateView(present.subList(0, present.size() - 1),
                candidate.instance(), candidateBox);
        int count = support.supporterBoxes().size();
        boolean violated = (rule.get().equals("covered")
                        && !SupportConstraint.touchesCorners(candidateBox, support.surfaces()))
                || (rule.get().equals("single") && count != 1)
                || (rule.get().equals("multiple") && count < 2);
        return violated
                ? Optional.of(new Violation("ground_contact_violation", candidate.instance().id()))
                : Optional.empty();
    }
}
