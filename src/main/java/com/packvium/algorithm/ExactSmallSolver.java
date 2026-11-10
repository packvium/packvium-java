package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.extension.CandidateScorer;
import com.packvium.objective.LandedCostSolutionScorer;
import com.packvium.objective.ShippingCostSolutionScorer;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Depth-first branch and bound over whole group batches.
 *
 * <p>Exact only for the discrete candidate model and the item-count objective, so it does not
 * publish a global optimality claim. O(product of per-batch candidate counts) in the worst case,
 * which is why it is gated on exactItemLimit.
 */
public final class ExactSmallSolver implements SingleContainerSolver {
    private final List<PlacementConstraint> constraints;
    private final CandidateScorer scorer;

    public ExactSmallSolver(List<PlacementConstraint> constraints, CandidateScorer scorer) {
        this.constraints = List.copyOf(constraints);
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "exact_small";
    }

    /**
     * Each item's own physical volume, not the state's used volume: that one deliberately
     * removes nesting overlap and is a different quantity.
     */
    private static BigInteger placedVolume(ContainerState state) {
        BigInteger volume = BigInteger.ZERO;
        for (Placement placement : state.placements) {
            volume = volume.add(placement.dimensions().volume());
        }
        return volume;
    }

    /**
     * Whether {@code candidate} should replace {@code incumbent}: more items placed wins, then more
     * placed volume. Without the second key an equal-count but worse arrangement found later
     * replaced a better one, so more search time made the answer worse.
     */
    public static boolean isBetterState(ContainerState candidate, ContainerState incumbent) {
        if (candidate.placements.size() != incumbent.placements.size()) {
            return candidate.placements.size() > incumbent.placements.size();
        }
        return placedVolume(candidate).compareTo(placedVolume(incumbent)) > 0;
    }

    private static long landedCharge(ContainerState state, PackingConfig config) {
        return LandedCostSolutionScorer.chargeOrUnpriceable(state.container,
                ShippingCostSolutionScorer.billedTicks(state.container, state.payloadTicks, config));
    }

    private static boolean isBetterForObjective(ContainerState candidate, BigInteger candidateVolume,
            ContainerState incumbent, BigInteger incumbentVolume, PackingConfig config) {
        int candidateCount = candidate.placements.size();
        int incumbentCount = incumbent.placements.size();
        if (candidateCount != incumbentCount) {
            return candidateCount > incumbentCount;
        }
        if (config.objective().equals(LandedCostSolutionScorer.OBJECTIVE_NAME)) {
            long candidateCharge = landedCharge(candidate, config);
            long incumbentCharge = landedCharge(incumbent, config);
            if (candidateCharge != incumbentCharge) {
                return candidateCharge < incumbentCharge;
            }
        }
        return candidateVolume.compareTo(incumbentVolume) > 0;
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        if (items.size() > config.exactItemLimit()) {
            throw new IllegalArgumentException("Exact-small item limit exceeded");
        }
        Search search = new Search(container, sequence, items, config,
                ConstraintSet.defaults(config.minimumSupportRatio(), constraints, List.of()), stats, deadline);
        try {
            search.dfs(0, new ContainerState(container, sequence), items.size(), BigInteger.ZERO);
        } catch (TimeLimitReached reached) {
            // The best state so far stands; the deadline flag below reports the cut.
        }
        Set<String> packed = new HashSet<>();
        search.best.placements.forEach(placement -> packed.add(placement.instance().id()));
        List<ItemInstance> unpacked = items.stream().filter(item -> !packed.contains(item.id())).toList();
        // Proves only the maximum item count reachable through the generated points; it stops at
        // the first full packing and does not minimise the rest of the objective vector.
        return new SingleContainerSolution(search.best, unpacked, false, deadline.expired(), false);
    }

    /** One branch-and-bound run; its incumbent is mutable per-call state. */
    private final class Search {
        private final List<List<ItemInstance>> batches;
        private final List<BigInteger> batchVolumes;
        private final BigInteger[] suffixVolumes;
        private final PackingConfig config;
        private final List<PlacementConstraint> constraints;
        private final SearchStats stats;
        private final Deadline deadline;
        private final int total;
        private ContainerState best;
        private BigInteger bestVolume = BigInteger.ZERO;

        Search(Container container, long sequence, List<ItemInstance> items, PackingConfig config,
                List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline) {
            this.batches = GroupBatcher.batches(items);
            this.batchVolumes = batches.stream()
                    .map(batch -> batch.stream().map(item -> item.dimensions().volume())
                            .reduce(BigInteger.ZERO, BigInteger::add))
                    .toList();
            this.suffixVolumes = new BigInteger[batches.size() + 1];
            suffixVolumes[batches.size()] = BigInteger.ZERO;
            for (int index = batches.size() - 1; index >= 0; index--) {
                suffixVolumes[index] = batchVolumes.get(index).add(suffixVolumes[index + 1]);
            }
            this.config = config;
            this.constraints = constraints;
            this.stats = stats;
            this.deadline = deadline;
            this.total = items.size();
            this.best = new ContainerState(container, sequence);
        }

        void dfs(int index, ContainerState state, int reachable, BigInteger stateVolume) {
            if (deadline.expired()) {
                return;
            }
            stats.searchNodesExpanded++;
            int stateCount = state.placements.size();
            if (isBetterForObjective(state, stateVolume, best, bestVolume, config)) {
                best = state;
                bestVolume = stateVolume;
            }
            if (index >= batches.size()) {
                return;
            }
            int potentialCount = stateCount + reachable;
            int bestCount = best.placements.size();
            if (potentialCount < bestCount) {
                return;
            }
            if (!config.objective().equals(LandedCostSolutionScorer.OBJECTIVE_NAME) && potentialCount == bestCount
                    && stateVolume.add(suffixVolumes[index]).compareTo(bestVolume) <= 0) {
                return;
            }
            List<ItemInstance> batch = batches.get(index);
            int remaining = reachable - batch.size();
            for (ContainerState child : BeamPacker.placeBatchExhaustively(state, batch, config, constraints, stats,
                    deadline, scorer)) {
                dfs(index + 1, child, remaining, stateVolume.add(batchVolumes.get(index)));
                if (best.placements.size() == total || deadline.expired()) {
                    return;
                }
            }
            dfs(index + 1, state, remaining, stateVolume);
        }
    }
}
