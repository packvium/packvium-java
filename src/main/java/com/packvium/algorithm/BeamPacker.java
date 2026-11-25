package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Nesting;
import com.packvium.domain.Placement;
import com.packvium.extension.CandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Greedy placement widened into a beam by maxCandidatesPerItem.
 *
 * <p>A width of one is plain best-fit greedy; wider keeps that many partial packings alive so an
 * early locally-best corner cannot dictate the whole layout. O(batches * width * branch * F) for F
 * the cost of one {@link CandidateFinder#find}, plus O(width * m log width) for the node keys,
 * O(f log f) per batch position to sort the f future items' volumes and weights and sum them once,
 * and O(log f) per node key to read the future bound off those prefix sums.
 */
public final class BeamPacker {
    private BeamPacker() {
    }

    public static SingleContainerSolution pack(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline,
            CandidateScorer scorer) {
        int branchWidth = (int) Math.max(1, config.maxCandidatesPerItem());
        long width = Math.max(1, config.containerPlanBeamWidth());
        if (width == 1) {
            return packLegacy(container, sequence, items, config, constraints, stats, deadline, scorer, branchWidth);
        }
        boolean byVolume = ranksByVolume(config);
        long nodeLimit = config.containerPlanNodeLimit();
        long nodes = 0;
        List<List<ItemInstance>> batches = GroupBatcher.batches(items);
        List<Node> beam = List.of(new Node(new ContainerState(container, sequence), List.of()));
        Node incumbent = greedyRollout(container, sequence, batches, config, constraints, stats, deadline, scorer);
        // nodeKey builds an O(m) placement signature; computing it once per node instead of once
        // per comparison changes no ordering.
        SortKey incumbentKey = nodeKey(incumbent, FutureBound.NONE, byVolume);
        long incumbentUnplaced = incumbent.unplaced().size();
        BigInteger incumbentUsed = incumbent.state().usedVolume;
        for (int position = 0; position < batches.size(); position++) {
            List<ItemInstance> batch = batches.get(position);
            List<Node> expansions = new ArrayList<>();
            boolean exhausted = false;
            for (Node node : beam) {
                if (nodes >= nodeLimit) {
                    exhausted = true;
                    break;
                }
                nodes++;
                stats.searchNodesExpanded++;
                List<ContainerState> children;
                try {
                    children = placeBatch(node.state(), batch, config, constraints, stats, deadline, scorer, branchWidth);
                } catch (TimeLimitReached reached) {
                    exhausted = true;
                    children = List.of();
                }
                for (ContainerState child : children) {
                    expansions.add(new Node(child, node.unplaced()));
                }
                expansions.add(new Node(node.state(), concat(node.unplaced(), batch)));
            }
            List<ItemInstance> future = GroupBatcher.flatten(batches.subList(position + 1, batches.size()));
            for (Node expansion : expansions) {
                long unplacedCount = expansion.unplaced().size() + future.size();
                // A worse leading component -- more unplaced items, or under maximum_volume less
                // used volume -- loses whatever follows, so the O(m) signature is built only for a
                // candidate that can win.
                if (byVolume ? expansion.state().usedVolume.compareTo(incumbentUsed) < 0
                        : unplacedCount > incumbentUnplaced) {
                    continue;
                }
                // The key of the expansion with every future item unplaced, without building that
                // O(n) list for a candidate that rarely wins.
                SortKey candidateKey = nodeKey(expansion.state(), unplacedCount, FutureBound.NONE, byVolume);
                if (candidateKey.compareTo(incumbentKey) < 0) {
                    incumbent = new Node(expansion.state(), concat(expansion.unplaced(), future));
                    incumbentKey = candidateKey;
                    incumbentUnplaced = unplacedCount;
                    incumbentUsed = expansion.state().usedVolume;
                }
            }
            if (expansions.isEmpty()) {
                break;
            }
            FutureBound bound = FutureBound.of(future, container.maxPayload().map(Weight::ticks));
            beam = first(StableSorter.sortBy(expansions, node -> nodeKey(node, bound, byVolume)), width);
            if (exhausted) {
                return new SingleContainerSolution(incumbent.state(), incumbent.unplaced(), false, deadline.expired(),
                        false);
            }
        }
        List<Node> ordered = StableSorter.sortBy(beam, node -> nodeKey(node, FutureBound.NONE, byVolume));
        Node chosen = !ordered.isEmpty()
                && nodeKey(ordered.get(0), FutureBound.NONE, byVolume).compareTo(incumbentKey) < 0
                ? ordered.get(0)
                : incumbent;
        return new SingleContainerSolution(chosen.state(), chosen.unplaced());
    }

    /** The anytime seed: one greedy pass, so a complete incumbent exists before the beam starts. */
    private static Node greedyRollout(Container container, long sequence, List<List<ItemInstance>> batches,
            PackingConfig config, List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline,
            CandidateScorer scorer) {
        ContainerState greedy = new ContainerState(container, sequence);
        List<ItemInstance> unplaced = new ArrayList<>();
        for (int position = 0; position < batches.size(); position++) {
            List<ContainerState> children;
            try {
                children = placeBatch(greedy, batches.get(position), config, constraints, stats, deadline, scorer, 1);
            } catch (TimeLimitReached reached) {
                unplaced.addAll(GroupBatcher.flatten(batches.subList(position, batches.size())));
                break;
            }
            if (children.isEmpty()) {
                unplaced.addAll(batches.get(position));
            } else {
                greedy = children.get(0);
            }
        }
        return new Node(greedy, unplaced);
    }

    /**
     * The historical fast/balanced candidate beam. The quality-only anytime seed above would
     * only double work at plan width one, so the low-latency path stays separate.
     */
    private static SingleContainerSolution packLegacy(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline,
            CandidateScorer scorer, int width) {
        boolean byVolume = ranksByVolume(config);
        List<List<ItemInstance>> batches = GroupBatcher.batches(items);
        List<Node> beam = List.of(new Node(new ContainerState(container, sequence), List.of()));
        for (int position = 0; position < batches.size(); position++) {
            List<ItemInstance> batch = batches.get(position);
            List<Node> expansions = new ArrayList<>();
            boolean exhausted = false;
            for (Node node : beam) {
                stats.searchNodesExpanded++;
                List<ContainerState> children;
                try {
                    children = placeBatch(node.state(), batch, config, constraints, stats, deadline, scorer, width);
                } catch (TimeLimitReached reached) {
                    exhausted = true;
                    children = List.of();
                }
                if (children.isEmpty()) {
                    expansions.add(new Node(node.state(), concat(node.unplaced(), batch)));
                } else {
                    children.forEach(child -> expansions.add(new Node(child, node.unplaced())));
                }
            }
            beam = first(StableSorter.sortBy(expansions, node -> nodeKey(node, FutureBound.NONE, byVolume)), width);
            if (exhausted) {
                Node best = beam.get(0);
                List<ItemInstance> pending = GroupBatcher.flatten(batches.subList(position + 1, batches.size()));
                return new SingleContainerSolution(best.state(), concat(best.unplaced(), pending), false, true, false);
            }
        }
        Node best = beam.get(0);
        return new SingleContainerSolution(best.state(), best.unplaced());
    }

    /** States reachable by placing every member of the batch, at most {@code width}, best first. */
    public static List<ContainerState> placeBatch(ContainerState state, List<ItemInstance> batch, PackingConfig config,
            List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline, CandidateScorer scorer,
            int width) {
        return placeBatch(state, batch, config, constraints, stats, deadline, scorer, Optional.of(width));
    }

    /** Every state reachable by placing every member of the batch, best first; empty if it cannot fit. */
    public static List<ContainerState> placeBatchExhaustively(ContainerState state, List<ItemInstance> batch,
            PackingConfig config, List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline,
            CandidateScorer scorer) {
        return placeBatch(state, batch, config, constraints, stats, deadline, scorer, Optional.empty());
    }

    private static List<ContainerState> placeBatch(ContainerState state, List<ItemInstance> batch,
            PackingConfig config, List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline,
            CandidateScorer scorer, Optional<Integer> width) {
        if (batch.size() == 1) {
            List<ContainerState> out = new ArrayList<>();
            for (Candidate candidate : CandidateFinder.find(state, batch.get(0), config, constraints, stats, deadline,
                    scorer, width, Optional.empty())) {
                out.add(extended(state, batch.get(0), candidate));
            }
            return out;
        }
        ContainerState working = state;
        for (ItemInstance item : batch) {
            List<Candidate> candidates = CandidateFinder.find(working, item, config, constraints, stats, deadline,
                    scorer, 1);
            if (candidates.isEmpty()) {
                return List.of();
            }
            working = extended(working, item, candidates.get(0));
        }
        return List.of(working);
    }

    public static ContainerState extended(ContainerState state, ItemInstance item, Candidate candidate) {
        ContainerState child = state.copy();
        child.add(new Placement(item, candidate.position(), candidate.rotation(), candidate.dimensions(),
                candidate.point(), candidate.envelopeDimensions(), SupportCalculator.ratio(state, item, candidate),
                Weight.ZERO));
        return child;
    }

    private static boolean ranksByVolume(PackingConfig config) {
        return config.objective().equals("maximum_volume");
    }

    private static SortKey nodeKey(Node node, FutureBound future, boolean byVolume) {
        return nodeKey(node.state(), node.unplaced().size(), future, byVolume);
    }

    /** Under maximum_volume the reachable and used volume lead, ahead of the unplaced count. */
    private static SortKey nodeKey(ContainerState state, long unplacedCount, FutureBound future, boolean byVolume) {
        if (byVolume) {
            return SortKey.of(future.reachableVolume(state).negate(), state.usedVolume.negate(), unplacedCount,
                    state.maxZ, state.placementSignature());
        }
        return SortKey.of(future.unpackedLowerBound(state, unplacedCount), unplacedCount,
                (long) -state.placements.size(), state.maxZ, state.usedVolume.negate(), state.placementSignature());
    }

    private static <T> List<T> concat(List<T> left, List<T> right) {
        List<T> joined = new ArrayList<>(left.size() + right.size());
        joined.addAll(left);
        joined.addAll(right);
        return joined;
    }

    private static <T> List<T> first(List<T> values, long count) {
        return new ArrayList<>(values.subList(0, (int) Math.min(count, values.size())));
    }

    /**
     * The batches still to come, summed once per beam position: the prefix sums of their volumes
     * ascending (absent when one nests, since nesting breaks the volume bound) and of their weights
     * ascending (absent when the container has no payload limit). Costs are non-negative, so the
     * sums never decrease and how many of the cheapest fit is one binary search: O(log f) per node
     * instead of the O(f) walk, with the same values.
     *
     * <p>Weight sums stop at the first that exceeds the payload, which no capacity of this
     * container can hold; comparing before adding keeps every sum inside a {@code long}.
     */
    private record FutureBound(int count, Optional<List<BigInteger>> volumes, Optional<long[]> weights) {
        static final FutureBound NONE = new FutureBound(0, Optional.empty(), Optional.empty());

        static FutureBound of(List<ItemInstance> future, Optional<Long> maxPayloadTicks) {
            if (future.isEmpty()) {
                return NONE;
            }
            Optional<List<BigInteger>> volumes = Optional.empty();
            if (future.stream().noneMatch(item -> item.item().nestingHeight().isPresent())) {
                List<BigInteger> sums = new ArrayList<>(future.size());
                BigInteger used = BigInteger.ZERO;
                // Occupied, not declared: the free space these are compared with is occupied
                // volume, and two wedges each declaring the whole crate both fit in it.
                for (BigInteger volume : future.stream().map(Nesting::itemOccupiedVolume).sorted().toList()) {
                    used = used.add(volume);
                    sums.add(used);
                }
                volumes = Optional.of(sums);
            }
            Optional<long[]> weights = maxPayloadTicks.map(payload -> weightSums(future, payload));
            return new FutureBound(future.size(), volumes, weights);
        }

        private static long[] weightSums(List<ItemInstance> future, long payload) {
            long[] sorted = future.stream().mapToLong(item -> item.weight().ticks()).sorted().toArray();
            long used = 0;
            int within = 0;
            for (long weight : sorted) {
                if (weight > payload - used) {
                    break;
                }
                used += weight;
                sorted[within++] = used;
            }
            return Arrays.copyOf(sorted, within);
        }

        /**
         * An upper bound on the volume this node can still end with: what is placed, plus the
         * future items' total volume capped by the free space. For nesting, nominal volumes are
         * not additive, so the free space alone is the bound.
         */
        BigInteger reachableVolume(ContainerState state) {
            if (count == 0) {
                return state.usedVolume;
            }
            BigInteger free = state.container.innerDimensions().volume().subtract(state.usedVolume).max(BigInteger.ZERO);
            BigInteger future = volumes.map(sums -> sums.get(sums.size() - 1)).orElse(free);
            return state.usedVolume.add(future.min(free));
        }

        /** How many items the node must leave unpacked at the least. O(log f) for f future items. */
        long unpackedLowerBound(ContainerState state, long unplacedCount) {
            if (count == 0) {
                return unplacedCount;
            }
            long possible = count;
            if (volumes.isPresent()) {
                BigInteger capacity = state.container.innerDimensions().volume().subtract(state.usedVolume);
                List<BigInteger> sums = volumes.get();
                possible = Math.min(possible, countWithin(sums.size(), index -> sums.get(index).compareTo(capacity) <= 0));
            }
            if (weights.isPresent() && state.container.maxPayload().isPresent()) {
                long capacity = Math.max(0, state.container.maxPayload().get().ticks() - state.payloadTicks);
                long[] sums = weights.get();
                possible = Math.min(possible, countWithin(sums.length, index -> sums[index] <= capacity));
            }
            return unplacedCount + count - possible;
        }

        /** The length of the prefix of a non-decreasing sequence whose sums fit: one binary search. */
        private static int countWithin(int size, IntPredicate fits) {
            int low = 0;
            int high = size;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (fits.test(middle)) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }

    /** A partial packing and the items it has given up on. */
    private record Node(ContainerState state, List<ItemInstance> unplaced) {
    }
}
