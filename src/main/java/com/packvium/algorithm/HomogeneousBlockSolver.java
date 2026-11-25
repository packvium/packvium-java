package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.internal.DirectSupportView;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.domain.ShapeType;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic solid single-type block search for unrestricted carton loading.
 *
 * <p>Candidate enumeration is O(B*S*T*R*X*Y) time and O(S+n) space: B committed blocks, S the
 * capped maximal-space count, T declared item types, R unique rotations, X/Y the block grid
 * extents. Both the deadline and the node limit bound the product explicitly. One
 * (space, type, rotation) group's X*Y nodes are summarised once and memoised by their four
 * integers, so a later round or the second mode that meets the same group costs O(1).
 */
public final class HomogeneousBlockSolver implements SingleContainerSolver {
    /** Both enumerations, the order {@code quality} and {@code balanced} run them in. */
    public static final List<Mode> MODES = List.of(Mode.COUNT, Mode.VOLUME);
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000);
    /** Distinct group shapes one block solve remembers; the memo only saves arithmetic. */
    private static final int LATTICE_MEMO_LIMIT = 4096;

    private final List<PlacementConstraint> constraints;
    private final List<Mode> modes;
    private final Optional<Long> nodeLimit;

    public HomogeneousBlockSolver(List<PlacementConstraint> constraints) {
        this(constraints, MODES, Optional.empty());
    }

    /**
     * {@code fast} and {@code balanced} leave {@code containerPlanNodeLimit} at one, which would
     * stop the lattice enumeration before its first block; their start carries its own bound.
     */
    public HomogeneousBlockSolver(List<PlacementConstraint> constraints, List<Mode> modes, Optional<Long> nodeLimit) {
        this.constraints = List.copyOf(constraints);
        this.modes = List.copyOf(modes);
        this.nodeLimit = nodeLimit;
    }

    /** The bound of the block start {@code fast} and {@code balanced} add, when this is one. */
    Optional<Long> nodeLimit() {
        return nodeLimit;
    }

    @Override
    public String name() {
        return "homogeneous_blocks";
    }

    @Override
    public boolean orderInsensitive() {
        return true;
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        if (!supports(container, items, config)) {
            return new ExtremePointSolver(constraints, new DefaultCandidateScorer())
                    .packOne(container, sequence, items, config, stats, deadline);
        }
        long limit = nodeLimit.orElse(config.containerPlanNodeLimit());
        boolean byVolume = config.objective().equals("maximum_volume");
        Map<Group, Lattice> lattices = new HashMap<>();
        SingleContainerSolution best = null;
        boolean reached = false;
        for (Mode mode : modes) {
            if (deadline.expired()) {
                reached = true;
                break;
            }
            SingleContainerSolution solution = packMode(container, sequence, items, config, stats, deadline, mode,
                    limit, lattices);
            reached = reached || solution.timeLimitReached();
            if (best == null || solutionKey(solution, byVolume).compareTo(solutionKey(best, byVolume)) < 0) {
                best = solution;
            }
        }
        if (best == null) {
            return new SingleContainerSolution(new ContainerState(container, sequence), items, false, reached, false);
        }
        return new SingleContainerSolution(best.state(), best.unpacked(), false, reached, false);
    }

    boolean supports(Container container, List<ItemInstance> items, PackingConfig config) {
        // A block set on a smaller one overhangs it, so a request that asks for support is left
        // to the per-item search.
        if (config.minimumSupportRatio() > 0) {
            return false;
        }
        if (!constraints.isEmpty() || !container.obstacles().isEmpty() || !container.preloaded().isEmpty()
                || container.axles().isPresent()
                || !container.tagLimits().isEmpty() || container.maxStackDensity().isPresent()
                || container.voidFillReserveRatio() > 0 || container.isFlatBag()) {
            return false;
        }
        return items.stream().map(ItemInstance::item).allMatch(HomogeneousBlockSolver::unrestricted);
    }

    private static boolean unrestricted(Item item) {
        // Block-on-block contacts would need a second implementation of the stacking-group rule;
        // the general solver already walks every edge.
        return item.shapeType() == ShapeType.RIGID_CUBOID && item.group().isEmpty() && item.tags().isEmpty() && item.incompatibleTags().isEmpty()
                && item.eligibleContainerTags().isEmpty() && item.stackable() && !item.mustBeOnFloor()
                && item.maxTopLoad().isEmpty() && item.maxStackedItems().isEmpty() && item.minimumSupportRatio() == 0.0
                && item.groundContactRule().map("free"::equals).orElse(true) && item.nestingHeight().isEmpty()
                && item.stopIndex().isEmpty() && item.acceptsStackingGroups().isEmpty();
    }

    private static SortKey solutionKey(SingleContainerSolution solution, boolean byVolume) {
        ContainerState state = solution.state();
        if (byVolume) {
            return SortKey.of(state.usedVolume.negate(), (long) solution.unpacked().size(), state.maxZ,
                    state.placementSignature());
        }
        return SortKey.of((long) solution.unpacked().size(), state.usedVolume.negate(), state.maxZ,
                state.placementSignature());
    }

    private static SingleContainerSolution packMode(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline, Mode mode, long limit,
            Map<Group, Lattice> lattices) {
        ContainerState state = new ContainerState(container, sequence);
        List<Space> spaces = List.of(new Space(Point.ORIGIN, container.innerDimensions()));
        // Keyed by item id in byte order, as the reference engine's ksort(SORT_STRING) does.
        Map<String, List<ItemInstance>> remaining = new TreeMap<>(SortKey::compareCodePoints);
        for (ItemInstance instance : items) {
            remaining.computeIfAbsent(instance.item().id(), key -> new ArrayList<>()).add(instance);
        }
        Map<String, List<Form>> forms = new HashMap<>();
        remaining.forEach((itemId, available) -> forms.put(itemId, forms(available.get(0), config)));
        long[] nodes = {0};
        boolean reached = false;
        while (!spaces.isEmpty() && remaining.values().stream().anyMatch(list -> !list.isEmpty())) {
            Optional<Block> best;
            try {
                best = nextBlock(container, state, spaces, remaining, forms, stats, deadline, mode, limit, lattices,
                        nodes);
            } catch (SearchCut cut) {
                reached = true;
                break;
            }
            if (best.isEmpty()) {
                break;
            }
            Block block = best.get();
            List<ItemInstance> available = remaining.get(block.itemId());
            List<ItemInstance> chosen = new ArrayList<>(available.subList(0, (int) block.count()));
            remaining.put(block.itemId(), new ArrayList<>(available.subList((int) block.count(), available.size())));
            commit(state, block, chosen, config, stats);
            Dimensions blockDimensions = Dimensions.ofTicks(block.nx() * block.envelope().length().ticks(),
                    block.ny() * block.envelope().width().ticks(), block.nz() * block.envelope().height().ticks());
            spaces = MaximalSpaceSolver.subtractAll(spaces,
                    List.of(new AxisAlignedBox(block.space().origin(), blockDimensions)), Optional.of(stats));
        }
        List<ItemInstance> unpacked = new ArrayList<>();
        remaining.values().forEach(unpacked::addAll);
        return new SingleContainerSolution(state, unpacked, false, reached || deadline.expired(), false);
    }

    /** Each unique rotation of a type with its envelope and physical volume. */
    private static List<Form> forms(ItemInstance prototype, PackingConfig config) {
        List<Form> forms = new ArrayList<>();
        for (OrientedDimensions oriented : prototype.dimensions().uniqueRotations(prototype.item().allowedRotations())) {
            Dimensions physical = oriented.dimensions();
            Dimensions envelope = config.clearance().ticks() != 0 ? physical.expand(config.clearance()) : physical;
            forms.add(new Form(oriented.rotation(), physical, envelope, physical.volume()));
        }
        return forms;
    }

    /** How many of a type one block may still take: stock, item limit and payload. */
    private static long capacity(Container container, ContainerState state, List<ItemInstance> available) {
        long capacity = available.size();
        if (container.maxItems().isPresent()) {
            capacity = Math.min(capacity, container.maxItems().get() - state.placementCount());
        }
        long weight = available.get(0).weight().ticks();
        if (container.maxPayload().isPresent() && weight > 0) {
            capacity = Math.min(capacity, (container.maxPayload().get().ticks() - state.payloadTicks) / weight);
        }
        return capacity;
    }

    /**
     * One round's winning block. A group's nodes are counted at once, and the stop the per-node
     * loop made is reproduced: its first node polls the deadline, and a counted limit falling
     * inside the group is charged exactly the nodes that loop expanded before stopping. A stop
     * discards the round ({@link SearchCut}).
     */
    private static Optional<Block> nextBlock(Container container, ContainerState state, List<Space> spaces,
            Map<String, List<ItemInstance>> remaining, Map<String, List<Form>> forms, SearchStats stats,
            Deadline deadline, Mode mode, long limit, Map<Group, Lattice> lattices, long[] nodes) {
        Optional<Long> ceiling = deadline.searchNodeCeiling(stats);
        // Capacity reads only the committed state, so it is fixed for the whole round.
        List<Map.Entry<String, Long>> types = new ArrayList<>();
        for (Map.Entry<String, List<ItemInstance>> entry : remaining.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            long capacity = capacity(container, state, entry.getValue());
            if (capacity > 0) {
                types.add(Map.entry(entry.getKey(), capacity));
            }
        }
        Block best = null;
        for (Space space : spaces) {
            Dimensions room = space.dimensions();
            for (Map.Entry<String, Long> type : types) {
                long capacity = type.getValue();
                for (Form form : forms.get(type.getKey())) {
                    long columns = Math.min(room.length().ticks() / form.envelope().length().ticks(), capacity);
                    long rows = room.width().ticks() / form.envelope().width().ticks();
                    if (columns == 0 || rows == 0) {
                        continue;
                    }
                    if (nodes[0] >= limit || deadline.expired()) {
                        throw new SearchCut();
                    }
                    Group group = new Group(columns, rows, room.height().ticks() / form.envelope().height().ticks(),
                            capacity);
                    Lattice lattice = lattices.get(group);
                    if (lattice == null) {
                        lattice = Lattice.of(group);
                        if (lattices.size() < LATTICE_MEMO_LIMIT) {
                            lattices.put(group, lattice);
                        }
                    }
                    long budget = limit - nodes[0];
                    if (ceiling.isPresent()) {
                        budget = Math.min(budget, ceiling.get() - stats.searchNodesExpanded);
                    }
                    if (lattice.nodes() > budget) {
                        stats.searchNodesExpanded += budget;
                        nodes[0] += budget;
                        throw new SearchCut();
                    }
                    nodes[0] += lattice.nodes();
                    stats.searchNodesExpanded += lattice.nodes();
                    if (lattice.count() == 0) {
                        continue;
                    }
                    BigInteger lead = mode == Mode.COUNT ? BigInteger.valueOf(-lattice.count())
                            : BigInteger.valueOf(lattice.count()).multiply(form.volume()).negate();
                    // The key's first field is -count or -used volume; a group that loses on it
                    // loses on the key, so its key is never built.
                    if (best == null || lead.compareTo(best.lead()) <= 0) {
                        best = betterBlock(best, mode, space, type.getKey(), form, lattice);
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code best}, or this group's winning block when its key ranks strictly ahead. */
    private static Block betterBlock(Block best, Mode mode, Space space, String itemId, Form form, Lattice lattice) {
        long count = lattice.count();
        BigInteger usedVolume = BigInteger.valueOf(count).multiply(form.volume());
        long spaceFill = usedVolume.multiply(PPM).divide(space.dimensions().volume()).longValueExact();
        List<Object> key = new ArrayList<>();
        if (mode == Mode.COUNT) {
            key.addAll(List.of(BigInteger.valueOf(-count), -spaceFill, usedVolume.negate()));
        } else {
            key.addAll(List.of(usedVolume.negate(), -spaceFill, -count));
        }
        key.addAll(List.of(space.origin().z(), space.origin().y(), space.origin().x(), itemId,
                form.rotation().name(), lattice.nx(), lattice.ny(), lattice.nz()));
        SortKey score = new SortKey(key);
        if (best != null && score.compareTo(best.score()) >= 0) {
            return best;
        }
        return new Block(space, itemId, form.rotation(), form.physical(), form.envelope(), lattice.nx(), lattice.ny(),
                lattice.nz(), count, (BigInteger) key.get(0), score);
    }
    private static void commit(ContainerState state, Block block, List<ItemInstance> chosen, PackingConfig config,
            SearchStats stats) {
        long clearance = config.clearance().ticks();
        int index = 0;
        Point origin = block.space().origin();
        for (long z = 0; z < block.nz(); z++) {
            for (long y = 0; y < block.ny(); y++) {
                for (long x = 0; x < block.nx(); x++) {
                    Point point = new Point(origin.x() + x * block.envelope().length().ticks(),
                            origin.y() + y * block.envelope().width().ticks(),
                            origin.z() + z * block.envelope().height().ticks());
                    Point position = new Point(point.x() + clearance, point.y() + clearance, point.z() + clearance);
                    ItemInstance member = chosen.get(index++);
                    // Above its bottom layer a member rests on an identical footprint; the bottom
                    // layer rests on whatever is there.
                    double support = z == 0 ? memberSupport(state, member, point, block.envelope()) : 1.0;
                    state.addDirect(new Placement(member, position, block.rotation(), block.physical(),
                            point, block.envelope(), support, Weight.ZERO));
                    stats.candidatesEvaluated++;
                    stats.placementsAttempted++;
                }
            }
        }
    }

    /**
     * The share of a member's base that rests on the floor or on a box beneath it. O(P) over the
     * container's placements, once per bottom-layer member.
     */
    private static double memberSupport(ContainerState state, ItemInstance instance, Point point,
            Dimensions envelope) {
        if (point.z() == 0) {
            return 1.0;
        }
        DirectSupportView support = LoadSupportGraph.candidateView(state.placements, instance,
                new AxisAlignedBox(point, envelope));
        return (double) support.supportingArea() / envelope.baseAreaTicks();
    }

    /** Which lead a round ranks blocks by: the most pieces, or the most volume. */
    public enum Mode {
        COUNT,
        VOLUME
    }

    private record Form(Rotation rotation, Dimensions physical, Dimensions envelope, BigInteger volume) {
    }

    /** A group's shape: block columns (capacity folded in), rows, layers and capacity. */
    private record Group(long columns, long rows, long layers, long capacity) {
    }

    /**
     * One group's (nx, ny) enumeration, summarised: how many nodes it has (counted whether or not
     * nz is positive) and its first largest count in (nx, ny) order. Within a group every key field
     * but the lead is equal, and the lead improves strictly with the count, so this is the only
     * node that can win. O(columns * rows) time, O(1) space.
     */
    private record Lattice(long nodes, long count, long nx, long ny, long nz) {
        static Lattice of(Group group) {
            long nodes = 0;
            long count = 0;
            long bestNx = 0;
            long bestNy = 0;
            long bestNz = 0;
            for (long nx = 1; nx <= group.columns(); nx++) {
                long maximumNy = Math.min(group.rows(), group.capacity() / nx);
                nodes += maximumNy;
                for (long ny = 1; ny <= maximumNy; ny++) {
                    long nz = Math.min(group.layers(), group.capacity() / (nx * ny));
                    if (nz > 0 && nx * ny * nz > count) {
                        count = nx * ny * nz;
                        bestNx = nx;
                        bestNy = ny;
                        bestNz = nz;
                    }
                }
            }
            return new Lattice(nodes, count, bestNx, bestNy, bestNz);
        }
    }

    private record Block(Space space, String itemId, Rotation rotation, Dimensions physical, Dimensions envelope,
            long nx, long ny, long nz, long count, BigInteger lead, SortKey score) {
    }

    /** The node limit or the deadline cut the enumeration; the incumbent of this round is dropped. */
    private static final class SearchCut extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SearchCut() {
            super(null, null, false, false);
        }
    }
}
