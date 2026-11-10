package com.packvium.validation;

import com.packvium.constraint.AxleLoad;
import com.packvium.constraint.CompatibilityConstraint;
import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.LoadUnit;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.SupportConstraint;
import com.packvium.constraint.TagCountConstraint;
import com.packvium.constraint.Violation;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.AxlePair;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Nesting;
import com.packvium.domain.Obstacle;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.UnpackedItem;
import com.packvium.policy.PhpValues;
import com.packvium.sequence.RouteSequenceError;
import com.packvium.sequence.UnloadingDependencyGraph;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
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
 * Re-derives every guarantee from the placements alone.
 *
 * <p>It shares no state with the solvers, so a solver that reports a placement it never
 * actually verified is caught here rather than reaching the caller.
 *
 * <p>Complexity, for {@code n} placements in one container: {@code O(n log n + n * a)} for the
 * collision sweep ({@code a} the per-level active set), {@code O(n * k)} for the per-placement
 * checks with {@code k} obstacle boxes, and {@code O(n^2)} only when pair-dependent rules
 * (tags, support) are present, since each placement then evaluates against all the others --
 * the same bound as the constraint checks the solvers themselves run.
 */
public final class IndependentSolutionValidator {
    private static final CompatibilityConstraint COMPATIBILITY = new CompatibilityConstraint();
    private static final TagCountConstraint TAG_COUNT = new TagCountConstraint();

    public ValidationReport validate(PackingRequest request, List<PackedContainer> containers) {
        return validate(request, containers, 0.0, Optional.empty(), Optional.empty());
    }

    public ValidationReport validate(PackingRequest request, List<PackedContainer> containers,
            double minimumSupportRatio) {
        return validate(request, containers, minimumSupportRatio, Optional.empty(), Optional.empty());
    }

    public ValidationReport validate(PackingRequest request, List<PackedContainer> containers,
            double minimumSupportRatio, Optional<Length> clearance) {
        return validate(request, containers, minimumSupportRatio, clearance, Optional.empty());
    }

    /** The packer's call: every placement and every unpacked item, so completeness is checked too. */
    public ValidationReport validate(PackingRequest request, List<PackedContainer> containers,
            double minimumSupportRatio, Length clearance, List<UnpackedItem> unpacked) {
        return validate(request, containers, minimumSupportRatio, Optional.of(clearance), Optional.of(unpacked));
    }

    public ValidationReport validate(PackingRequest request, List<PackedContainer> containers,
            double minimumSupportRatio, Optional<Length> clearance, Optional<List<UnpackedItem>> unpacked) {
        List<ValidationIssue> issues = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> expected = new LinkedHashSet<>();
        Map<String, Long> inventory = new LinkedHashMap<>();
        long clearanceTicks = clearance.map(Length::ticks).orElse(0L);
        for (ItemInstance instance : request.instances()) {
            expected.add(instance.id());
        }
        for (PackedContainer packed : containers) {
            inventory.merge(packed.container().id(), 1L, Long::sum);
            validateContainer(packed, minimumSupportRatio, clearanceTicks, seen, issues);
        }
        for (Container container : request.containers()) {
            if (container.quantity().isPresent()
                    && inventory.getOrDefault(container.id(), 0L) > container.quantity().get()) {
                issues.add(new ValidationIssue("container_inventory_exceeded", container.id()));
            }
        }
        if (unpacked.isPresent()) {
            for (UnpackedItem item : unpacked.get()) {
                String id = item.instance().id();
                if (seen.contains(id)) {
                    issues.add(new ValidationIssue("duplicate_item", id + " is both packed and unpacked"));
                }
                seen.add(id);
                if (item.reason().isEmpty()) {
                    issues.add(new ValidationIssue("missing_reason", id));
                }
            }
            for (String id : sortedIds(expected.stream().filter(id -> !seen.contains(id)).toList())) {
                issues.add(new ValidationIssue("missing_item", id));
            }
        }
        for (String id : sortedIds(seen.stream().filter(id -> !expected.contains(id)).toList())) {
            issues.add(new ValidationIssue("unknown_item", id));
        }
        for (String detail : splitGroups(containers)) {
            issues.add(new ValidationIssue("group_split", detail));
        }
        if (unpacked.isPresent()) {
            for (String detail : partialGroups(request, containers)) {
                issues.add(new ValidationIssue("group_partial", detail));
            }
        }
        return new ValidationReport(issues.isEmpty(), issues);
    }

    private static void validateContainer(PackedContainer packed, double minimumSupportRatio, long clearanceTicks,
            Set<String> seen, List<ValidationIssue> issues) {
        Container container = packed.container();
        AxisAlignedBox boundary = new AxisAlignedBox(new Point(0, 0, 0), container.innerDimensions());
        long payload = 0;
        List<Placement> placements = packed.placements();
        boolean compatibilitySensitive = false;
        boolean supportSensitive = minimumSupportRatio > 0;
        boolean stackSensitive = false;
        for (Placement placement : placements) {
            Item item = placement.instance().item();
            if (!item.tags().isEmpty() || !item.incompatibleTags().isEmpty()) {
                compatibilitySensitive = true;
            }
            if (item.minimumSupportRatio() > 0
                    || (item.groundContactRule().isPresent() && !item.groundContactRule().get().equals("free"))) {
                supportSensitive = true;
            }
            if (!item.stackable()) {
                stackSensitive = true;
            }
        }
        LoadSupportGraph stackGraph = stackSensitive ? new LoadSupportGraph(LoadCalculator.units(placements)) : null;
        for (long[] pair : collisionPairs(placements)) {
            issues.add(new ValidationIssue("collision", placements.get((int) pair[0]).instance().id() + " with "
                    + placements.get((int) pair[1]).instance().id()));
        }
        List<PlacementConstraint> constraints = new ArrayList<>();
        if (compatibilitySensitive) {
            constraints.add(COMPATIBILITY);
            constraints.add(TAG_COUNT);
        }
        if (supportSensitive) {
            constraints.add(new SupportConstraint(minimumSupportRatio));
        }
        for (int index = 0; index < placements.size(); index++) {
            Placement p = placements.get(index);
            String id = p.instance().id();
            if (!seen.add(id)) {
                issues.add(new ValidationIssue("duplicate_item", id));
            }
            payload = Math.addExact(payload, p.instance().weight().ticks());
            checkGeometry(p, container, boundary, clearanceTicks, issues);
            // The common lattice case has no pair-dependent rules. Avoid building an O(n)
            // "all other placements" list for every item in that case.
            if (!constraints.isEmpty()) {
                List<Placement> others = new ArrayList<>(placements.size() - 1);
                others.addAll(placements.subList(0, index));
                others.addAll(placements.subList(index + 1, placements.size()));
                ConstraintContext context = new ConstraintContext(container, others, p.instance(),
                        p.envelopeOrigin(), p.rotation(), p.dimensions(), p.envelopeDimensions());
                for (PlacementConstraint constraint : constraints) {
                    ConstraintResult result = constraint.evaluate(context);
                    if (!result.allowed()) {
                        issues.add(new ValidationIssue(result.code(), id + ": " + result.detail()));
                    }
                }
            }
            if (stackGraph != null) {
                stackGraph.nonStackableFailure(placements, p.instance(), index)
                        .ifPresent(failure -> issues.add(
                                new ValidationIssue(failure.code(), id + ": " + failure.detail())));
            }
        }
        checkLoads(packed, issues);
        boolean onRoute = placements.stream().anyMatch(p -> p.instance().item().stopIndex().isPresent());
        if (onRoute) {
            unloadingOrderViolation(packed).ifPresent(issues::add);
        }
        if (container.maxPayload().isPresent() && payload > container.maxPayload().get().ticks()) {
            issues.add(new ValidationIssue("payload_exceeded", packed.id()));
        }
        if (container.maxItems().isPresent() && placements.size() > container.maxItems().get()) {
            issues.add(new ValidationIssue("max_items_exceeded", packed.id()));
        }
    }

    /** The checks that need one placement and its container only. O(k) for k obstacle boxes. */
    private static void checkGeometry(Placement p, Container container, AxisAlignedBox boundary,
            long clearanceTicks, List<ValidationIssue> issues) {
        String id = p.instance().id();
        Item item = p.instance().item();
        if (!boundary.contains(p.envelopeBox())) {
            issues.add(new ValidationIssue("outside_container", id));
        }
        if (!item.allowedRotations().contains(p.rotation())) {
            issues.add(new ValidationIssue("forbidden_rotation", id));
        }
        Dimensions expectedDims = item.dimensions().rotated(p.rotation());
        if (!sameTicks(p.dimensions(), expectedDims)) {
            issues.add(new ValidationIssue("dimension_mismatch", id));
        }
        if (!envelopeMatches(p, clearanceTicks)) {
            issues.add(new ValidationIssue("clearance_mismatch", id));
        }
        for (Obstacle obstacle : container.obstacles()) {
            for (AxisAlignedBox box : obstacle.boxes()) {
                if (Placement.hitsBox(p, box)) {
                    issues.add(new ValidationIssue("obstacle_collision", id));
                }
            }
        }
        if (item.mustBeOnFloor() && p.envelopeOrigin().z() != 0) {
            issues.add(new ValidationIssue("must_be_on_floor", id + ": "));
        }
        List<String> eligibleTags = item.eligibleContainerTags();
        if (!eligibleTags.isEmpty() && Collections.disjoint(eligibleTags, container.tags())) {
            issues.add(new ValidationIssue("container_ineligible", id));
        }
    }

    /**
     * A second, whole-container bearing pass: the per-placement check reports the first
     * offender it meets, this one is anchored on the container.
     */
    private static void checkLoads(PackedContainer packed, List<ValidationIssue> issues) {
        Container container = packed.container();
        List<LoadUnit> units = LoadCalculator.units(packed.placements());
        Optional<Long> densityLimit = container.maxStackDensity().map(Weight::ticks);
        Optional<Violation> failure = LoadCalculator.overloaded(units)
                .or(() -> LoadCalculator.crushed(units))
                .or(() -> LoadCalculator.stackLimitExceeded(units))
                .or(() -> LoadCalculator.stackDensityExceeded(units, densityLimit));
        failure.ifPresent(f -> issues.add(new ValidationIssue(f.code(), packed.id() + ": " + f.detail())));
        if (container.axles().isPresent()) {
            AxlePair axles = container.axles().get();
            AxleLoad.exceeded(axles, units, container.tareWeight().ticks(),
                            container.innerDimensions().length().ticks())
                    .ifPresent(f -> issues.add(new ValidationIssue(f.code(), packed.id() + ": " + f.detail())));
        }
    }

    /**
     * Colliding placement index pairs, ascending.
     *
     * <p>A valid nested pair is excluded: it is a deliberate, exactly bounded overlap
     * between two identical items, one sunk into the other, not a collision.
     *
     * <p>Pruning the sweep's active set by x-overlap alone leaves a large multi-layer lattice
     * (many z-levels that all share similar x ranges) with an active set that stays close to
     * O(n), turning the sweep into O(n * activeSize) instead of the intended near-linear
     * behaviour. Bucketing by z-level first, mirroring ContactGraph's
     * ContactLevelIndex, bounds each bucket's sweep by that level's own density instead of the
     * whole container's.
     */
    private static List<long[]> collisionPairs(List<Placement> placements) {
        if (placements.isEmpty()) {
            return List.of();
        }
        long cell = 1;
        for (Placement placement : placements) {
            AxisAlignedBox box = placement.envelopeBox();
            cell = Math.max(cell, box.z2() - box.origin().z());
        }
        Map<Long, List<Integer>> buckets = new LinkedHashMap<>();
        for (int index = 0; index < placements.size(); index++) {
            AxisAlignedBox box = placements.get(index).envelopeBox();
            long low = Math.floorDiv(box.origin().z(), cell);
            long high = Math.floorDiv(box.z2() - 1, cell);
            buckets.computeIfAbsent(low, key -> new ArrayList<>()).add(index);
            if (high != low) {
                buckets.computeIfAbsent(high, key -> new ArrayList<>()).add(index);
            }
        }
        // Encoded as (min << 32 | max): natural long order is the lexicographic pair order.
        TreeSet<Long> pairs = new TreeSet<>();
        for (List<Integer> indices : buckets.values()) {
            sweep(placements, indices, pairs);
        }
        List<long[]> out = new ArrayList<>(pairs.size());
        for (long encoded : pairs) {
            out.add(new long[] {encoded >>> 32, encoded & 0xFFFFFFFFL});
        }
        return out;
    }

    private static void sweep(List<Placement> placements, List<Integer> indices, Set<Long> pairs) {
        List<long[]> ordered = new ArrayList<>(indices.size());
        for (int index : indices) {
            AxisAlignedBox box = placements.get(index).envelopeBox();
            ordered.add(new long[] {box.origin().x(), box.x2(), index});
        }
        ordered.sort((a, b) -> {
            for (int k = 0; k < 3; k++) {
                int c = Long.compare(a[k], b[k]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        List<long[]> active = new ArrayList<>();
        for (long[] entry : ordered) {
            long x1 = entry[0];
            int index = (int) entry[2];
            active.removeIf(other -> other[0] <= x1);
            for (long[] other : active) {
                int otherIndex = (int) other[1];
                if (Placement.collide(placements.get(index), placements.get(otherIndex))
                        && !Nesting.isValidNesting(placements.get(index), placements.get(otherIndex))) {
                    long low = Math.min(index, otherIndex);
                    long high = Math.max(index, otherIndex);
                    pairs.add(low << 32 | high);
                }
            }
            active.add(new long[] {entry[1], index});
        }
    }

    /**
     * Whether this container's route can actually be unloaded stop by stop, using the
     * same geometry {@code UnloadingDependencyGraph.safeRouteRemovalOrder} already proves safe
     * removal orders against -- no separate notion of "blocked" for this check to disagree with
     * the one the sequence module uses.
     *
     * <p>Physical, not envelope, boxes: reachability is about what a forklift or hand would
     * actually collide with, and clearance is a solver placement margin, not a real obstruction.
     */
    private static Optional<ValidationIssue> unloadingOrderViolation(PackedContainer packed) {
        List<Placement> placements = packed.placements();
        List<AxisAlignedBox> boxes = placements.stream().map(Placement::box).toList();
        List<Optional<Long>> stops = placements.stream().map(p -> p.instance().item().stopIndex()).toList();
        try {
            UnloadingDependencyGraph.safeRouteRemovalOrder(boxes, stops, packed.container().innerDimensions());
        } catch (RouteSequenceError error) {
            List<String> stuckIds = sortedIds(
                    error.stuck().stream().map(i -> placements.get(i).instance().id()).toList());
            return Optional.of(new ValidationIssue(
                    "unloading_order_violation",
                    packed.id() + ": stop " + error.stop() + " cannot be fully unloaded ("
                            + String.join(", ", stuckIds) + " still blocked)"));
        }
        return Optional.empty();
    }

    private static boolean envelopeMatches(Placement p, long clearanceTicks) {
        Dimensions expected = clearanceTicks != 0 ? p.dimensions().expand(new Length(clearanceTicks)) : p.dimensions();
        return sameTicks(p.envelopeDimensions(), expected)
                && p.position().x() == p.envelopeOrigin().x() + clearanceTicks
                && p.position().y() == p.envelopeOrigin().y() + clearanceTicks
                && p.position().z() == p.envelopeOrigin().z() + clearanceTicks;
    }

    private static boolean sameTicks(Dimensions left, Dimensions right) {
        return left.length().ticks() == right.length().ticks()
                && left.width().ticks() == right.width().ticks()
                && left.height().ticks() == right.height().ticks();
    }

    /** Groups packed into more than one container, by group name. O(n log n). */
    private static List<String> splitGroups(List<PackedContainer> containers) {
        Map<String, Set<String>> located = new LinkedHashMap<>();
        for (PackedContainer packed : containers) {
            for (Placement placement : packed.placements()) {
                Optional<String> group = placement.instance().item().group();
                if (group.isPresent()) {
                    located.computeIfAbsent(group.get(), key -> new LinkedHashSet<>()).add(packed.id());
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String group : sortedIds(new ArrayList<>(located.keySet()))) {
            Set<String> where = located.get(group);
            if (where.size() <= 1) {
                continue;
            }
            out.add(group + ": " + String.join(", ", sortedIds(new ArrayList<>(where))));
        }
        return out;
    }

    /** Groups some but not all of whose members were packed. O(n log n). */
    private static List<String> partialGroups(PackingRequest request, List<PackedContainer> containers) {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        Map<String, Set<String>> placed = new LinkedHashMap<>();
        for (ItemInstance instance : request.instances()) {
            instance.item().group().ifPresent(group ->
                    expected.computeIfAbsent(group, key -> new LinkedHashSet<>()).add(instance.id()));
        }
        for (PackedContainer packed : containers) {
            for (Placement placement : packed.placements()) {
                placement.instance().item().group().ifPresent(group ->
                        placed.computeIfAbsent(group, key -> new LinkedHashSet<>()).add(placement.instance().id()));
            }
        }
        List<String> out = new ArrayList<>();
        for (String group : sortedIds(new ArrayList<>(expected.keySet()))) {
            int members = expected.get(group).size();
            int count = placed.getOrDefault(group, Set.of()).size();
            if (count > 0 && count < members) {
                out.add(group + ": " + count + "/" + members + " packed");
            }
        }
        return out;
    }

    /** PHP's {@code sort}/{@code ksort} over identifiers, so a numeric group name orders as PHP's does. */
    private static List<String> sortedIds(List<String> ids) {
        return PhpValues.sorted(ids);
    }
}
