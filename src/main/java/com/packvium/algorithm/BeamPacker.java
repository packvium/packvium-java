package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.extension.CandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Greedy placement widened into a beam by maxCandidatesPerItem.
 *
 * <p>A width of one is plain best-fit greedy; wider keeps that many partial packings alive so an
 * early locally-best corner cannot dictate the whole layout. O(batches * width * branch * F) for F
 * the cost of one {@link CandidateFinder#find}, plus O(width * m log width) for the node keys.
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
        long nodeLimit = config.containerPlanNodeLimit();
        long nodes = 0;
        List<List<ItemInstance>> batches = GroupBatcher.batches(items);
        List<Node> beam = List.of(new Node(new ContainerState(container, sequence), List.of()));
        Node incumbent = greedyRollout(container, sequence, batches, config, constraints, stats, deadline, scorer);
        // nodeKey builds an O(m) placement signature and a knapsack bound; computing it once per
        // node instead of once per comparison changes no ordering.
        SortKey incumbentKey = nodeKey(incumbent, List.of());
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
                Node candidate = new Node(expansion.state(), concat(expansion.unplaced(), future));
                SortKey candidateKey = nodeKey(candidate, List.of());
                if (candidateKey.compareTo(incumbentKey) < 0) {
                    incumbent = candidate;
                    incumbentKey = candidateKey;
                }
            }
            if (expansions.isEmpty()) {
                break;
            }
            beam = first(StableSorter.sortBy(expansions, node -> nodeKey(node, future)), width);
            if (exhausted) {
                return new SingleContainerSolution(incumbent.state(), incumbent.unplaced(), false, deadline.expired(),
                        false);
            }
        }
        List<Node> ordered = StableSorter.sortBy(beam, node -> nodeKey(node, List.of()));
        Node chosen = !ordered.isEmpty() && nodeKey(ordered.get(0), List.of()).compareTo(incumbentKey) < 0
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
            beam = first(StableSorter.sortBy(expansions, node -> nodeKey(node, List.of())), width);
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

    /** How many of the cheapest future items could still fit by volume alone. O(f log f). */
    private static long maximumCountByVolume(List<ItemInstance> future, BigInteger capacity) {
        List<BigInteger> costs = new ArrayList<>(future.stream().map(item -> item.dimensions().volume()).toList());
        costs.sort(BigInteger::compareTo);
        BigInteger used = BigInteger.ZERO;
        long count = 0;
        for (BigInteger cost : costs) {
            BigInteger next = used.add(cost);
            if (next.compareTo(capacity) > 0) {
                break;
            }
            used = next;
            count++;
        }
        return count;
    }

    private static long unpackedLowerBound(ContainerState state, List<ItemInstance> unplaced,
            List<ItemInstance> future) {
        if (future.isEmpty()) {
            return unplaced.size();
        }
        long possible = future.size();
        if (future.stream().noneMatch(item -> item.item().nestingHeight().isPresent())) {
            BigInteger capacity = state.container.innerDimensions().volume().subtract(state.usedVolume);
            possible = Math.min(possible, maximumCountByVolume(future, capacity));
        }
        if (state.container.maxPayload().isPresent()) {
            long capacity = Math.max(0, state.container.maxPayload().get().ticks() - state.payloadTicks);
            long[] weights = future.stream().mapToLong(item -> item.weight().ticks()).sorted().toArray();
            long used = 0;
            long count = 0;
            for (long weight : weights) {
                if (used + weight > capacity) {
                    break;
                }
                used += weight;
                count++;
            }
            possible = Math.min(possible, count);
        }
        return unplaced.size() + future.size() - possible;
    }

    private static SortKey nodeKey(Node node, List<ItemInstance> future) {
        ContainerState state = node.state();
        return SortKey.of(unpackedLowerBound(state, node.unplaced(), future), (long) node.unplaced().size(),
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

    /** A partial packing and the items it has given up on. */
    private record Node(ContainerState state, List<ItemInstance> unplaced) {
    }
}
