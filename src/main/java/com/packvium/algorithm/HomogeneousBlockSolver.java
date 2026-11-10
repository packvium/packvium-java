package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic solid single-type block search for unrestricted carton loading.
 *
 * <p>Candidate enumeration is O(B*S*T*R*X*Y) time and O(S+n) space: B committed blocks, S the
 * capped maximal-space count, T declared item types, R unique rotations, X/Y the block grid
 * extents. Both the deadline and containerPlanNodeLimit bound the product explicitly.
 */
public final class HomogeneousBlockSolver implements SingleContainerSolver {
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000);

    private final List<PlacementConstraint> constraints;

    public HomogeneousBlockSolver(List<PlacementConstraint> constraints) {
        this.constraints = List.copyOf(constraints);
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
        if (!supports(container, items)) {
            return new ExtremePointSolver(constraints, new DefaultCandidateScorer())
                    .packOne(container, sequence, items, config, stats, deadline);
        }
        SingleContainerSolution best = null;
        boolean reached = false;
        for (Mode mode : Mode.values()) {
            if (deadline.expired()) {
                reached = true;
                break;
            }
            SingleContainerSolution solution = packMode(container, sequence, items, config, stats, deadline, mode);
            reached = reached || solution.timeLimitReached();
            if (best == null || solutionKey(solution).compareTo(solutionKey(best)) < 0) {
                best = solution;
            }
        }
        if (best == null) {
            return new SingleContainerSolution(new ContainerState(container, sequence), items, false, reached, false);
        }
        return new SingleContainerSolution(best.state(), best.unpacked(), false, reached, false);
    }

    private boolean supports(Container container, List<ItemInstance> items) {
        if (!constraints.isEmpty() || !container.obstacles().isEmpty() || container.axles().isPresent()
                || !container.tagLimits().isEmpty() || container.maxStackDensity().isPresent()
                || container.voidFillReserveRatio() > 0) {
            return false;
        }
        return items.stream().map(ItemInstance::item).allMatch(HomogeneousBlockSolver::unrestricted);
    }

    private static boolean unrestricted(Item item) {
        return item.group().isEmpty() && item.tags().isEmpty() && item.incompatibleTags().isEmpty()
                && item.eligibleContainerTags().isEmpty() && item.stackable() && !item.mustBeOnFloor()
                && item.maxTopLoad().isEmpty() && item.maxStackedItems().isEmpty() && item.minimumSupportRatio() == 0.0
                && item.groundContactRule().map("free"::equals).orElse(true) && item.nestingHeight().isEmpty()
                && item.stopIndex().isEmpty();
    }

    private static SortKey solutionKey(SingleContainerSolution solution) {
        return SortKey.of((long) solution.unpacked().size(), solution.state().usedVolume.negate(),
                solution.state().maxZ, solution.state().placementSignature());
    }

    private static SingleContainerSolution packMode(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline, Mode mode) {
        ContainerState state = new ContainerState(container, sequence);
        List<Space> spaces = List.of(new Space(Point.ORIGIN, container.innerDimensions()));
        // Keyed by item id in byte order, as the reference engine's ksort(SORT_STRING) does.
        Map<String, List<ItemInstance>> remaining = new TreeMap<>(SortKey::compareCodePoints);
        for (ItemInstance instance : items) {
            remaining.computeIfAbsent(instance.item().id(), key -> new ArrayList<>()).add(instance);
        }
        long[] nodes = {0};
        boolean reached = false;
        while (!spaces.isEmpty() && remaining.values().stream().anyMatch(list -> !list.isEmpty())) {
            Optional<Block> best;
            try {
                best = bestBlock(container, state, spaces, remaining, config, stats, deadline, mode, nodes);
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

    private static Optional<Block> bestBlock(Container container, ContainerState state, List<Space> spaces,
            Map<String, List<ItemInstance>> remaining, PackingConfig config, SearchStats stats, Deadline deadline,
            Mode mode, long[] nodes) {
        Block best = null;
        for (Space space : spaces) {
            BigInteger spaceVolume = space.dimensions().volume();
            for (Map.Entry<String, List<ItemInstance>> entry : remaining.entrySet()) {
                List<ItemInstance> available = entry.getValue();
                if (available.isEmpty()) {
                    continue;
                }
                ItemInstance prototype = available.get(0);
                long capacity = available.size();
                if (container.maxItems().isPresent()) {
                    capacity = Math.min(capacity, container.maxItems().get() - state.placementCount());
                }
                if (container.maxPayload().isPresent() && prototype.weight().ticks() > 0) {
                    capacity = Math.min(capacity,
                            (container.maxPayload().get().ticks() - state.payloadTicks) / prototype.weight().ticks());
                }
                if (capacity <= 0) {
                    continue;
                }
                for (OrientedDimensions oriented : prototype.dimensions()
                        .uniqueRotations(prototype.item().allowedRotations())) {
                    best = bestInRotation(best, space, spaceVolume, entry.getKey(), oriented, capacity, config, stats,
                            deadline, mode, nodes);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Block bestInRotation(Block incumbent, Space space, BigInteger spaceVolume, String itemId,
            OrientedDimensions oriented, long capacity, PackingConfig config, SearchStats stats, Deadline deadline,
            Mode mode, long[] nodes) {
        Block best = incumbent;
        Dimensions physical = oriented.dimensions();
        Dimensions envelope = config.clearance().ticks() != 0 ? physical.expand(config.clearance()) : physical;
        long maximumX = space.dimensions().length().ticks() / envelope.length().ticks();
        long maximumY = space.dimensions().width().ticks() / envelope.width().ticks();
        long maximumZ = space.dimensions().height().ticks() / envelope.height().ticks();
        for (long nx = 1; nx <= Math.min(maximumX, capacity); nx++) {
            long maximumNy = Math.min(maximumY, capacity / nx);
            for (long ny = 1; ny <= maximumNy; ny++) {
                if (nodes[0] >= config.containerPlanNodeLimit() || deadline.expired()) {
                    throw new SearchCut();
                }
                nodes[0]++;
                stats.searchNodesExpanded++;
                long nz = Math.min(maximumZ, capacity / (nx * ny));
                if (nz <= 0) {
                    continue;
                }
                long count = nx * ny * nz;
                BigInteger usedVolume = BigInteger.valueOf(count).multiply(physical.volume());
                long spaceFill = usedVolume.multiply(PPM).divide(spaceVolume).longValueExact();
                List<Object> key = new ArrayList<>();
                if (mode == Mode.COUNT) {
                    key.addAll(List.of(-count, -spaceFill, usedVolume.negate()));
                } else {
                    key.addAll(List.of(usedVolume.negate(), -spaceFill, -count));
                }
                key.addAll(List.of(space.origin().z(), space.origin().y(), space.origin().x(), itemId,
                        oriented.rotation().name(), nx, ny, nz));
                SortKey score = new SortKey(key);
                if (best == null || score.compareTo(best.score()) < 0) {
                    best = new Block(space, itemId, oriented.rotation(), physical, envelope, nx, ny, nz, count, score);
                }
            }
        }
        return best;
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
                    state.addDirect(new Placement(chosen.get(index++), position, block.rotation(), block.physical(),
                            point, block.envelope(), 1.0, Weight.ZERO));
                    stats.candidatesEvaluated++;
                    stats.placementsAttempted++;
                }
            }
        }
    }

    private enum Mode {
        COUNT,
        VOLUME
    }

    private record Block(Space space, String itemId, Rotation rotation, Dimensions physical, Dimensions envelope,
            long nx, long ny, long nz, long count, SortKey score) {
    }

    /** The node limit or the deadline cut the enumeration; the incumbent of this round is dropped. */
    private static final class SearchCut extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SearchCut() {
            super(null, null, false, false);
        }
    }
}
