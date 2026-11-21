package com.packvium.algorithm;

import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.support.CanonicalJson;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import com.packvium.validation.FixedPlacementException;
import com.packvium.validation.IndependentSolutionValidator;
import com.packvium.validation.ValidationIssue;
import com.packvium.validation.ValidationReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

/**
 * Admission of a request's fixed placements (docs/PLAN-REVISIONS.md).
 *
 * <p>A fixed placement is an item already in a known place: loaded, or locked there by an
 * operator. It enters the solve as a real {@link Placement} -- weight, support, top load and all
 * -- seeded into the one container instance it names, so every rule the engine already enforces
 * holds for it with no rule of its own.
 *
 * <p>What this class adds is the refusal. A fixed set that is not a valid packing on its own is
 * refused before any search, with {@code invalid_fixed_placement}, rather than exempted: an exempt
 * item would need a second validator, and four engines would have to agree on which rules it
 * skips. The check is the ordinary {@link IndependentSolutionValidator} run over the fixed set
 * alone, with item accounting left out because the free items have not been placed yet.
 */
public final class FixedPlacementAdmission {
    private FixedPlacementAdmission() {
    }

    /**
     * Resolve {@code request.fixedPlacements()}, or refuse the request.
     *
     * <p>O(f log f) to resolve f entries, plus one validator pass over them, which is O(f^2) in
     * the worst case of its pairwise sweep, and O(f^2) for the support ratios, each measured
     * against every other fixed item in its container. It runs once, before search.
     */
    public static FixedLoad admit(PackingRequest request, double minimumSupportRatio, Length clearance,
            Optional<Long> maxContainers) {
        if (request.fixedPlacements().isEmpty()) {
            return new FixedLoad(List.of(), request.instances());
        }
        Map<String, Item> items = new HashMap<>();
        request.items().forEach(item -> items.put(item.id(), item));
        Map<String, Container> containers = new HashMap<>();
        request.containers().forEach(container -> containers.put(container.id(), container));
        for (FixedPlacement entry : request.fixedPlacements()) {
            requireKnown(entry, items, containers);
        }
        List<ItemInstance> instances = assignInstances(request.fixedPlacements(), items);
        List<Group> grouped = groupByContainer(request, instances, clearance);
        requireContiguousInstances(grouped, containers, maxContainers);
        List<PackedContainer> packed = new ArrayList<>();
        for (Group group : grouped) {
            packed.add(new PackedContainer(containers.get(group.containerId()), group.instance(),
                    withSupportAndLoads(group.placements())));
        }
        requireValidAlone(request, packed, minimumSupportRatio, clearance);
        Set<String> taken = new HashSet<>();
        packed.forEach(container -> container.placements().forEach(placement -> taken.add(placement.instance().id())));
        List<ItemInstance> free = request.instances().stream().filter(instance -> !taken.contains(instance.id()))
                .toList();
        return new FixedLoad(packed, free);
    }

    private static void requireKnown(FixedPlacement entry, Map<String, Item> items, Map<String, Container> containers) {
        Item item = items.get(entry.itemId());
        if (item == null) {
            throw new FixedPlacementException("unknown item type " + CanonicalJson.spelling(entry.itemId()));
        }
        if (!containers.containsKey(entry.containerId())) {
            throw new FixedPlacementException("unknown container type " + CanonicalJson.spelling(entry.containerId()));
        }
        if (!item.allowedRotations().contains(entry.rotation())) {
            throw new FixedPlacementException(entry.itemId() + " may not be placed in orientation "
                    + entry.rotation().name());
        }
    }

    /** Fixed items take the first instances of their type, in the order they are listed. */
    private static List<ItemInstance> assignInstances(List<FixedPlacement> entries, Map<String, Item> items) {
        Map<String, Long> taken = new HashMap<>();
        List<ItemInstance> instances = new ArrayList<>();
        for (FixedPlacement entry : entries) {
            Item item = items.get(entry.itemId());
            long count = taken.merge(item.id(), 1L, Long::sum);
            if (count > item.quantity()) {
                throw new FixedPlacementException(count + " " + item.id() + " fixed, " + item.quantity() + " requested");
            }
            instances.add(new ItemInstance(item, count));
        }
        return instances;
    }

    /** One container instance a fixed placement names, with the placements it holds. */
    private record Group(String containerId, long instance, List<Placement> placements) {
    }

    private static List<Group> groupByContainer(PackingRequest request, List<ItemInstance> instances,
            Length clearance) {
        Map<String, Group> grouped = new LinkedHashMap<>();
        for (int index = 0; index < request.fixedPlacements().size(); index++) {
            FixedPlacement entry = request.fixedPlacements().get(index);
            Group group = grouped.computeIfAbsent(entry.packedContainerId(),
                    key -> new Group(entry.containerId(), entry.containerInstance(), new ArrayList<>()));
            group.placements().add(placement(entry, instances.get(index), clearance));
        }
        Map<String, Integer> order = new HashMap<>();
        for (int position = 0; position < request.containers().size(); position++) {
            order.put(request.containers().get(position).id(), position);
        }
        return StableSorter.sortBy(new ArrayList<>(grouped.values()),
                group -> SortKey.of(order.get(group.containerId()), group.instance()));
    }

    private static Placement placement(FixedPlacement entry, ItemInstance instance, Length clearance) {
        long margin = clearance.ticks();
        Point position = entry.position();
        // The clearance envelope must be inside the container, as it must for any placement; an
        // item flush against a wall has an envelope that starts before it.
        if (Math.min(position.x(), Math.min(position.y(), position.z())) < margin) {
            throw new FixedPlacementException("outside_container: " + instance.id());
        }
        Dimensions dimensions = instance.dimensions().rotated(entry.rotation());
        // A far corner past the 64-bit range would overflow mid-geometry; no container reaches it,
        // so the item is outside, as the reference's unbounded integers find it.
        if (farCornerOverflows(position, dimensions, margin)) {
            throw new FixedPlacementException("outside_container: " + instance.id());
        }
        Point envelopeOrigin = new Point(position.x() - margin, position.y() - margin, position.z() - margin);
        Dimensions envelope = margin > 0 ? dimensions.expand(clearance) : dimensions;
        return new Placement(instance, position, entry.rotation(), dimensions, envelopeOrigin, envelope, 1.0,
                Weight.ZERO, true);
    }

    private static boolean farCornerOverflows(Point position, Dimensions dimensions, long margin) {
        return position.x() > Long.MAX_VALUE - dimensions.length().ticks() - margin
                || position.y() > Long.MAX_VALUE - dimensions.width().ticks() - margin
                || position.z() > Long.MAX_VALUE - dimensions.height().ticks() - margin;
    }

    private static void requireContiguousInstances(List<Group> grouped, Map<String, Container> containers,
            Optional<Long> maxContainers) {
        Map<String, List<Long>> named = new LinkedHashMap<>();
        for (Group group : grouped) {
            named.computeIfAbsent(group.containerId(), key -> new ArrayList<>()).add(group.instance());
        }
        for (Map.Entry<String, List<Long>> entry : named.entrySet()) {
            List<Long> sequences = entry.getValue();
            int count = sequences.size();
            if (!sequences.equals(LongStream.rangeClosed(1, count).boxed().toList())) {
                throw new FixedPlacementException(entry.getKey() + " instances " + CanonicalJson.spelling(sequences)
                        + " are not numbered 1.." + count);
            }
            Optional<Long> quantity = containers.get(entry.getKey()).quantity();
            if (quantity.isPresent() && count > quantity.get()) {
                throw new FixedPlacementException(count + " " + entry.getKey() + " named, " + quantity.get()
                        + " available");
            }
        }
        if (maxContainers.isPresent() && grouped.size() > maxContainers.get()) {
            throw new FixedPlacementException(grouped.size() + " containers hold fixed items, max_containers is "
                    + maxContainers.get());
        }
    }

    /**
     * Each fixed item's support ratio from the fixed items under it, and its top load.
     *
     * <p>Support is measured against every other fixed item in the container, not only the ones
     * listed before it: the set is one arrangement, and listing order is not physics.
     */
    private static List<Placement> withSupportAndLoads(List<Placement> placements) {
        List<Placement> supported = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            List<Placement> others = new ArrayList<>(placements);
            others.remove(index);
            supported.add(new Placement(placement.instance(), placement.position(), placement.rotation(),
                    placement.dimensions(), placement.envelopeOrigin(), placement.envelopeDimensions(),
                    supportRatio(others, placement), Weight.ZERO, true));
        }
        return TopLoadAssigner.assign(supported);
    }

    private static double supportRatio(List<Placement> others, Placement placement) {
        if (placement.envelopeOrigin().z() == 0) {
            return 1.0;
        }
        AxisAlignedBox box = new AxisAlignedBox(placement.envelopeOrigin(), placement.envelopeDimensions());
        long area = LoadSupportGraph.candidateView(others, placement.instance(), box).supportingArea();
        return (double) area / placement.envelopeDimensions().baseAreaTicks();
    }

    private static void requireValidAlone(PackingRequest request, List<PackedContainer> packed,
            double minimumSupportRatio, Length clearance) {
        ValidationReport report = new IndependentSolutionValidator().validate(request, packed, minimumSupportRatio,
                Optional.of(clearance), Optional.empty());
        if (!report.valid()) {
            ValidationIssue issue = report.issues().get(0);
            throw new FixedPlacementException(issue.code() + ": " + issue.detail());
        }
    }
}
