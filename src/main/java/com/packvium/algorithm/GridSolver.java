package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.LatticeSummary;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.domain.ShapeType;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Regular lattice for a single item type.
 *
 * <p>The lattice cannot express per-item physical rules, so every rule a regular stack could
 * break is folded into the layer count and capacity before any placement is made; anything it
 * cannot honour falls back to the general solver. O(r) to choose the lattice, O(n) to lay it
 * out, O(r) for the compact form.
 */
public final class GridSolver implements SingleContainerSolver {
    @Override
    public String name() {
        return "grid";
    }

    @Override
    public boolean orderInsensitive() {
        return true;
    }

    /**
     * Everything that decides an item's behaviour in a lattice, keyed by achievable physical
     * footprint rather than declared id: the same carton listed under two SKUs, one
     * rotated, is one physical item.
     */
    private static List<Object> latticeProfile(Item item) {
        List<String> footprints = new ArrayList<>();
        for (OrientedDimensions oriented : item.dimensions().uniqueRotations(item.allowedRotations())) {
            Dimensions physical = oriented.dimensions();
            footprints.add(physical.length().ticks() + "x" + physical.width().ticks() + "x" + physical.height().ticks());
        }
        footprints.sort(null);
        return Arrays.asList(footprints, item.weight().ticks(), item.stackable(), item.mustBeOnFloor(),
                item.maxTopLoad().map(Weight::ticks).orElse(null), sorted(item.tags()),
                sorted(item.incompatibleTags()), sorted(item.eligibleContainerTags()),
                item.groundContactRule().orElse(null),
                // Nesting overlap is valid only within one declared type: never borrow a
                // prototype's overlap allowance for another SKU.
                item.nestingHeight().isEmpty() ? null : item.id(),
                item.nestingHeight().map(height -> height.ticks()).orElse(null), item.group().orElse(null),
                // Fusing a column limit of 1 with an unlimited item, or stop 0 with stop 1, would
                // produce a tiling neither allows.
                item.maxStackedItems().orElse(null), item.stopIndex().orElse(null));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(SortKey::compareCodePoints);
        return copy;
    }

    public boolean supports(List<ItemInstance> items) {
        if (items.isEmpty()) {
            return false;
        }
        Set<List<Object>> profiles = new HashSet<>();
        for (ItemInstance item : items) {
            profiles.add(latticeProfile(item.item()));
        }
        return profiles.size() == 1;
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        if (mustDelegate(container, items)) {
            return new ExtremePointSolver(List.of(), new DefaultCandidateScorer())
                    .packOne(container, sequence, items, config, stats, deadline);
        }
        Item prototype = items.get(0).item();
        ContainerState state = new ContainerState(container, sequence);
        Lattice lattice = bestLattice(container, prototype, config);
        long total = Math.min(lattice.capacity(), items.size());
        // A group is all-or-nothing: a lattice that cannot hold every member holds none.
        if (prototype.group().isPresent() && total < items.size()) {
            return new SingleContainerSolution(state, items, false, false, true);
        }
        long nestingTicks = prototype.nestingHeight().map(height -> height.ticks()).orElse(0L);
        List<ItemInstance> placedSlice = items.subList(0, (int) total);
        boolean sameDeclaredType = placedSlice.stream().map(item -> item.item().id()).distinct().count() == 1;
        if (total > 0 && !config.requirePlacementCoordinates() && nestingTicks == 0 && sameDeclaredType) {
            // Compact form: the lattice already determines every coordinate, so the
            // per-instance loop is skipped. Only for one declared id, which the summary records.
            LatticeSummary summary = new LatticeSummary(prototype.id(), lattice.rotation(), lattice.physical(),
                    lattice.envelope(), lattice.nx(), lattice.ny(), lattice.layerStep(), config.clearance().ticks(),
                    total, prototype.weight().ticks());
            state.addLattice(summary, placedSlice);
            stats.searchNodesExpanded++;
            stats.candidatePointsConsidered += total;
            stats.candidatesEvaluated += total;
            stats.placementsAttempted += total;
            return new SingleContainerSolution(state, items.subList((int) total, items.size()));
        }
        long placed = layOut(state, items, lattice, total, config, stats, deadline);
        if (prototype.group().isPresent() && placed < items.size()) {
            return new SingleContainerSolution(new ContainerState(container, sequence), items, false,
                    deadline.expired(), true);
        }
        return new SingleContainerSolution(state, items.subList((int) placed, items.size()), false,
                placed < total && deadline.expired(), true);
    }

    private boolean mustDelegate(Container container, List<ItemInstance> items) {
        if (!container.obstacles().isEmpty() || items.isEmpty() || !supports(items)) {
            // A mixed-type list would place every item with the first item's dimensions.
            return true;
        }
        Item prototype = items.get(0).item();
        if (prototype.tags().stream().anyMatch(prototype.incompatibleTags()::contains)) {
            return true;
        }
        if (!prototype.eligibleContainerTags().isEmpty()
                && prototype.eligibleContainerTags().stream().noneMatch(container.tags()::contains)) {
            return true;
        }
        // Stack density needs the cumulative column load, axles the changing moment, and a hull
        // or a compressible item per-candidate geometry: the closed form sees none of them.
        return container.voidFillReserveRatio() > 0 || container.maxStackDensity().isPresent()
                || container.axles().isPresent() || prototype.shapeType() != ShapeType.RIGID_CUBOID;
    }

    private static Lattice bestLattice(Container container, Item prototype, PackingConfig config) {
        // Every non-floor cell has one full-area direct supporter, which satisfies ratio 1,
        // covered and single; multiple is satisfiable only on the floor, so cap the layers.
        boolean singleLayer = prototype.mustBeOnFloor() || !prototype.stackable()
                || Objects.equals(prototype.groundContactRule().orElse(null), "multiple");
        // Zero unless the item sinks into an identical one beneath it.
        long nestingTicks = prototype.nestingHeight().map(height -> height.ticks()).orElse(0L);
        Lattice best = null;
        SortKey bestScore = null;
        var inner = container.innerDimensions();
        for (OrientedDimensions oriented : prototype.dimensions().uniqueRotations(prototype.allowedRotations())) {
            Dimensions physical = oriented.dimensions();
            Dimensions envelope = config.clearance().ticks() != 0 ? physical.expand(config.clearance()) : physical;
            long nx = inner.length().ticks() / envelope.length().ticks();
            long ny = inner.width().ticks() / envelope.width().ticks();
            long layerStep = envelope.height().ticks() - nestingTicks;
            long innerHeight = inner.height().ticks();
            long nz = innerHeight < envelope.height().ticks() ? 0 : (innerHeight - envelope.height().ticks()) / layerStep + 1;
            if (singleLayer) {
                nz = Math.min(nz, 1);
            }
            // A uniform column's bottom item bears (nz - 1) * weight, so the safe layer count is
            // floor(maxTopLoad / weight) + 1; weightless items leave the geometry alone.
            if (prototype.maxTopLoad().isPresent() && prototype.weight().ticks() > 0) {
                long layersAbove = prototype.maxTopLoad().get().ticks() / prototype.weight().ticks();
                if (layersAbove < nz - 1) {
                    nz = layersAbove + 1;
                }
            }
            if (prototype.maxStackedItems().isPresent()) {
                nz = Math.min(nz, prototype.maxStackedItems().get() + 1);
            }
            long capacity = capacity(container, prototype, Math.multiplyExact(Math.multiplyExact(nx, ny), nz));
            SortKey score = SortKey.of(-capacity, envelope.volume(), oriented.rotation().name());
            if (bestScore == null || score.compareTo(bestScore) < 0) {
                bestScore = score;
                best = new Lattice(oriented.rotation(), physical, envelope, nx, ny, layerStep, capacity);
            }
        }
        return best;
    }

    private static long capacity(Container container, Item prototype, long cells) {
        long capacity = cells;
        if (container.maxItems().isPresent()) {
            capacity = Math.min(capacity, container.maxItems().get());
        }
        if (container.maxPayload().isPresent() && prototype.weight().ticks() > 0) {
            capacity = Math.min(capacity, container.maxPayload().get().ticks() / prototype.weight().ticks());
        }
        for (String tag : prototype.tags()) {
            Long limit = container.tagLimits().get(tag);
            if (limit != null) {
                capacity = Math.min(capacity, limit);
            }
        }
        return capacity;
    }

    private static long layOut(ContainerState state, List<ItemInstance> items, Lattice lattice, long total,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        long clearance = config.clearance().ticks();
        Dimensions physical = lattice.physical();
        long placed = 0;
        for (int index = 0; index < total; index++) {
            if (deadline.expired()) {
                break;
            }
            stats.searchNodesExpanded++;
            stats.candidatePointsConsidered++;
            long x = index % lattice.nx();
            long y = index / lattice.nx() % lattice.ny();
            long z = index / (lattice.nx() * lattice.ny());
            Point point = new Point(x * lattice.envelope().length().ticks(), y * lattice.envelope().width().ticks(),
                    z * lattice.layerStep());
            Point position = new Point(point.x() + clearance, point.y() + clearance, point.z() + clearance);
            // A mixed-declared-type lattice shares footprints, not rotation labels, so the stored
            // rotation is looked up per item.
            ItemInstance item = items.get(index);
            OrientedDimensions own = item.item().dimensions().uniqueRotations(item.item().allowedRotations()).stream()
                    .filter(candidate -> candidate.dimensions().equals(physical)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("supports() guarantees a matching rotation"));
            state.addDirect(new Placement(item, position, own.rotation(), own.dimensions(), point, lattice.envelope(),
                    1.0, Weight.ZERO));
            placed++;
            stats.candidatesEvaluated++;
            stats.placementsAttempted++;
        }
        return placed;
    }

    private record Lattice(Rotation rotation, Dimensions physical, Dimensions envelope, long nx, long ny,
            long layerStep, long capacity) {
    }
}
