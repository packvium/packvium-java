package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.UnpackedItem;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.objective.LandedCostSolutionScorer;
import com.packvium.validation.IndependentSolutionValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Redistributes payload weight across an already-solved multi-container packing.
 *
 * <p>An explicit, opt-in post-processing pass: call it after {@code Packer.pack()}, never run
 * inside it. {@code unpacked} should be the list {@code Packer.pack()} returned (empty for a
 * complete packing); it is passed straight through to the validator, so full accounting is checked
 * against every requested instance, not only the ones sitting in a container.
 *
 * <p>Such a post-pass has a well-known failure mode: it can silently drop an item, because its own
 * bookkeeping only checks whether the box count is still 1. This class never substitutes a partial
 * signal for the real thing. Every candidate move is simulated in full and checked against the same
 * {@link IndependentSolutionValidator} every solve is checked against, and only committed once that
 * check passes; see {@link #attemptMove} for what that guarantees.
 *
 * <p>Greedy and best-effort, not globally optimal: each round moves at most one item, from the
 * heaviest container, trying its placements heaviest first against the other containers lightest
 * first, and commits the first combination that fits and strictly reduces the gap between the
 * heaviest and lightest payload. That gap is then a strictly decreasing sequence of non-negative
 * integers, so the pass terminates on its own, bounded again by {@code maxMoves}. Each round tries
 * at most P x (c - 1) moves for P placements in the heaviest of c containers, each one candidate
 * search plus one validation of the whole packing.
 */
public final class WeightRebalancer {
    public static final int DEFAULT_MAX_MOVES = 64;
    public static final long DEFAULT_TIME_LIMIT_MS = 1000;

    private WeightRebalancer() {
    }

    /** {@link #rebalance(PackingRequest, List, List, PackingConfig, int, long)} with the defaults. */
    public static RebalanceResult rebalance(PackingRequest request, List<PackedContainer> containers,
            List<UnpackedItem> unpacked, PackingConfig config) {
        return rebalance(request, containers, unpacked, config, DEFAULT_MAX_MOVES, DEFAULT_TIME_LIMIT_MS);
    }

    /** A complete packing under the default configuration. */
    public static RebalanceResult rebalance(PackingRequest request, List<PackedContainer> containers) {
        return rebalance(request, containers, List.of(), PackingConfig.balanced());
    }

    public static RebalanceResult rebalance(PackingRequest request, List<PackedContainer> containers,
            List<UnpackedItem> unpacked, PackingConfig config, int maxMoves, long timeLimitMs) {
        LandedCostSolutionScorer.requirePriceableObjective(config, request.containers());
        // Under lowest_landed_cost an input that already bills past a rate table's last bracket has
        // no published price to preserve: it is refused in Packer's words rather than shuffled.
        // A no-op for every other objective.
        LandedCostSolutionScorer.requirePriceable(containers, config);
        IndependentSolutionValidator validator = new IndependentSolutionValidator();
        Deadline deadline = Deadline.ofMilliseconds(timeLimitMs);
        List<PackedContainer> working = List.copyOf(containers);
        List<WeightMove> moves = new ArrayList<>();
        for (int round = 0; round < maxMoves; round++) {
            if (working.size() < 2 || deadline.expired()) {
                break;
            }
            Optional<Move> committed = round(request, validator, working, unpacked, config, deadline);
            if (committed.isEmpty()) {
                break;
            }
            moves.add(committed.get().move());
            working = committed.get().containers();
        }
        return new RebalanceResult(working, moves);
    }

    /** The first sound move that narrows the payload spread, or empty when none does. */
    private static Optional<Move> round(PackingRequest request, IndependentSolutionValidator validator,
            List<PackedContainer> working, List<UnpackedItem> unpacked, PackingConfig config, Deadline deadline) {
        long[] weights = working.stream().mapToLong(container -> container.payloadWeight().ticks()).toArray();
        long spread = max(weights) - min(weights);
        if (spread <= 0) {
            return Optional.empty();
        }
        int sourceIndex = argMax(weights);
        PackedContainer source = working.get(sourceIndex);
        List<Placement> placements = source.expandPlacements();
        List<Integer> rankedItems = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            rankedItems.add(index);
        }
        // Heaviest first; List.sort is stable, so equal weights keep placement order.
        rankedItems.sort((left, right) -> Long.compare(placements.get(right).instance().weight().ticks(),
                placements.get(left).instance().weight().ticks()));
        List<Integer> destinations = new ArrayList<>();
        for (int index = 0; index < working.size(); index++) {
            if (index != sourceIndex) {
                destinations.add(index);
            }
        }
        destinations.sort((left, right) -> Long.compare(weights[left], weights[right]));
        for (int placementIndex : rankedItems) {
            Placement moving = placements.get(placementIndex);
            long weightTicks = moving.instance().weight().ticks();
            if (moving.fixed() || weightTicks <= 0) {
                continue;
            }
            for (int destinationIndex : destinations) {
                long[] projected = weights.clone();
                projected[sourceIndex] -= weightTicks;
                projected[destinationIndex] += weightTicks;
                if (max(projected) - min(projected) >= spread) {
                    continue;
                }
                Optional<List<PackedContainer>> trial;
                try {
                    trial = attemptMove(request, validator, working, unpacked, sourceIndex, destinationIndex,
                            placementIndex, config, deadline);
                } catch (TimeLimitReached reached) {
                    trial = Optional.empty();
                }
                if (trial.isPresent()) {
                    return Optional.of(new Move(trial.get(), new WeightMove(moving.instance().id(), source.id(),
                            working.get(destinationIndex).id())));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Try relocating one placement; the new container list if it is sound, else empty.
     *
     * <p>Every candidate is proven, not assumed: the item must find a real, constraint-respecting
     * spot in the destination ({@link CandidateFinder#find}, the search a solve itself uses), and
     * the whole resulting set of containers must then pass the independent validator. Both container
     * entries are only ever replaced together, in the returned list, so there is no observable state
     * in which the item belongs to neither container. A move that fails geometrically, physically
     * (the validator refuses it, for example because something rested on the item that moved) or
     * commercially (under lowest_landed_cost the destination would bill past its tariff's last
     * bracket) is not made, and the caller's list is untouched.
     */
    private static Optional<List<PackedContainer>> attemptMove(PackingRequest request,
            IndependentSolutionValidator validator, List<PackedContainer> containers, List<UnpackedItem> unpacked,
            int sourceIndex, int destinationIndex, int placementIndex, PackingConfig config, Deadline deadline) {
        PackedContainer source = containers.get(sourceIndex);
        PackedContainer destination = containers.get(destinationIndex);
        List<Placement> sourcePlacements = source.expandPlacements();
        List<Placement> destinationPlacements = destination.expandPlacements();
        Placement moving = sourcePlacements.get(placementIndex);

        ContainerState destinationState = new ContainerState(destination.container(), destination.sequence());
        destinationPlacements.forEach(destinationState::add);
        List<PlacementConstraint> constraints = ConstraintSet.defaults(config.minimumSupportRatio(), List.of(),
                List.of());
        List<Candidate> candidates = CandidateFinder.find(destinationState, moving.instance(), config, constraints,
                new SearchStats(), deadline, new DefaultCandidateScorer(), 1);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Candidate candidate = candidates.get(0);
        Placement landed = new Placement(moving.instance(), candidate.position(), candidate.rotation(),
                candidate.dimensions(), candidate.point(), candidate.envelopeDimensions(),
                SupportCalculator.ratio(destinationState, moving.instance(), candidate));
        List<Placement> remaining = new ArrayList<>(sourcePlacements);
        remaining.remove(placementIndex);
        List<Placement> received = new ArrayList<>(destinationPlacements);
        received.add(landed);

        List<PackedContainer> trial = new ArrayList<>(containers);
        trial.set(sourceIndex, new PackedContainer(source.container(), source.sequence(),
                TopLoadAssigner.assign(remaining)));
        trial.set(destinationIndex, new PackedContainer(destination.container(), destination.sequence(),
                TopLoadAssigner.assign(received)));
        if (!validator.validate(request, trial, config.minimumSupportRatio(), config.clearance(), unpacked).valid()) {
            return Optional.empty();
        }
        // A move can be sound yet lift the destination's billed weight past its tariff's last
        // bracket; under lowest_landed_cost that trades a shippable packing for one with no price.
        if (LandedCostSolutionScorer.unpriceableContainer(trial, config).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(trial));
    }

    private static int argMax(long[] weights) {
        int best = 0;
        for (int index = 1; index < weights.length; index++) {
            if (weights[index] > weights[best]) {
                best = index;
            }
        }
        return best;
    }

    private static long max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    private static long min(long[] values) {
        return Arrays.stream(values).min().orElseThrow();
    }

    /** A committed round: the containers after the move, and the move. */
    private record Move(List<PackedContainer> containers, WeightMove move) {
    }
}
