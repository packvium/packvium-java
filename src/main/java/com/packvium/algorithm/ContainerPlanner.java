package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.FlatBag;
import com.packvium.domain.MadeToMeasure;
import com.packvium.domain.Nesting;
import com.packvium.domain.Sides;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.UnpackedItem;
import com.packvium.extension.ContainerSelector;
import com.packvium.extension.DefaultContainerSelector;
import com.packvium.objective.LandedCostSolutionScorer;
import com.packvium.objective.ShippingCostSolutionScorer;
import com.packvium.objective.ObjectiveRegistry;
import com.packvium.objective.ObjectiveScore;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spreads one start's items across containers: greedily, one container per round, or as a beam
 * over whole container plans when containerPlanBeamWidth exceeds one.
 */
final class ContainerPlanner {
    private final ContainerSelector selector;
    private final UnpackedReasons reasons;

    ContainerPlanner(ContainerSelector selector, UnpackedReasons reasons) {
        this.selector = selector;
        this.reasons = reasons;
    }

    /** What one start produced across every container it opened. */
    record Outcome(List<PackedContainer> packed, List<UnpackedItem> unpacked, boolean proven, boolean reached,
            boolean dominantLattice) {
    }

    /** Containers tried cheapest first, then smallest, then by id. */
    static List<Container> ordered(List<Container> containers) {
        return StableSorter.sortBy(containers, container -> SortKey.of(container.costMinor(),
                container.innerDimensions().volume(), container.id()));
    }

    static long maximumContainers(List<Container> containers, List<ItemInstance> items, PackingConfig config) {
        return config.maxContainers().orElseGet(() -> containers.stream()
                .mapToLong(container -> container.quantity().orElse((long) items.size())).sum());
    }

    /**
     * The plan every start continues from: each container holding fixed items, filled first and
     * kept whatever it gets.
     *
     * <p>A container that search cannot fill -- the deadline has passed, or the solver raised it --
     * is kept with its fixed items alone: dropping it would drop items the request says are already
     * loaded. O(k * S) for k fixed containers and S one {@code packOne}.
     */
    static Opened openPreloaded(SingleContainerSolver solver, List<PackedContainer> preloaded,
            List<ItemInstance> items, List<Container> containers, PackingConfig config, SearchStats stats,
            Deadline deadline) {
        List<PackedContainer> packed = new ArrayList<>();
        List<ItemInstance> remaining = items;
        Map<String, Long> inventory = new LinkedHashMap<>();
        Map<String, Long> sequences = new LinkedHashMap<>();
        for (Container container : containers) {
            inventory.put(container.id(), container.quantity().orElse(null));
            sequences.put(container.id(), 0L);
        }
        boolean exhaustive = true;
        boolean dominant = true;
        boolean reached = false;
        for (PackedContainer fixed : preloaded) {
            Container seeded = fixed.container().withPreloaded(fixed.placements());
            SingleContainerSolution one = null;
            if (!reached && !deadline.expired()) {
                try {
                    one = solver.packOne(seeded, fixed.sequence(), remaining, config, stats, deadline);
                } catch (TimeLimitReached cut) {
                    one = null;
                }
            }
            if (one == null || one.timeLimitReached()) {
                reached = true;
            }
            Committed committed = commit(one == null ? new ContainerState(seeded, fixed.sequence()) : one.state());
            packed.add(committed.packed());
            remaining = remaining.stream().filter(item -> !committed.ids().contains(item.id())).toList();
            if (inventory.get(seeded.id()) != null) {
                inventory.merge(seeded.id(), -1L, Long::sum);
            }
            sequences.put(seeded.id(), fixed.sequence());
            exhaustive = exhaustive && one != null && one.exhaustive();
            dominant = dominant && one != null && one.dominantLattice();
        }
        return new Opened(new Plan(packed, remaining, inventory, sequences, exhaustive, dominant), reached);
    }

    /** Where a start's search begins, and whether opening the fixed containers already hit a limit. */
    record Opened(Plan plan, boolean reached) {
    }

    /** Greedy rounds: each round packs every container type and commits the best one. O(rounds * c * S). */
    Outcome greedy(SingleContainerSolver solver, List<ItemInstance> items, List<Container> containers,
            PackingConfig config, SearchStats stats, Deadline deadline, Opened opened) {
        List<ItemInstance> remaining = opened.plan().remaining();
        List<PackedContainer> packed = new ArrayList<>(opened.plan().packed());
        boolean reached = opened.reached();
        boolean exhaustive = opened.plan().exhaustive();
        boolean dominantLattice = opened.plan().dominant();
        Map<String, Long> inventory = new HashMap<>(opened.plan().inventory());
        Map<String, Long> sequence = new HashMap<>(opened.plan().sequences());
        long maximum = maximumContainers(containers, items, config);
        List<Container> ordered = ordered(containers);
        while (!remaining.isEmpty() && packed.size() < maximum && !reached) {
            if (deadline.expired()) {
                reached = true;
                break;
            }
            List<Trial> trials = new ArrayList<>();
            for (Container container : ordered) {
                Long left = inventory.get(container.id());
                if (left != null && left <= 0) {
                    continue;
                }
                SingleContainerSolution one;
                try {
                    one = trial(solver, container, sequence.get(container.id()) + 1, remaining, config, stats,
                            deadline).solution();
                } catch (TimeLimitReached cut) {
                    reached = true;
                    break;
                }
                if (one.timeLimitReached()) {
                    reached = true;
                }
                if (one.state().placementCount() == 0) {
                    if (reached) {
                        break;
                    }
                    continue;
                }
                trials.add(new Trial(roundScore(one, remaining.size(), config), one));
                if (reached) {
                    break;
                }
            }
            if (trials.isEmpty()) {
                break;
            }
            SingleContainerSolution best = StableSorter.sortBy(trials, Trial::score).get(0).solution();
            Container chosen = best.state().container;
            sequence.merge(chosen.id(), 1L, Long::sum);
            exhaustive = exhaustive && best.exhaustive();
            dominantLattice = dominantLattice && best.dominantLattice();
            if (inventory.get(chosen.id()) != null) {
                inventory.merge(chosen.id(), -1L, Long::sum);
            }
            Committed committed = commit(best.state());
            packed.add(committed.packed());
            remaining = remaining.stream().filter(item -> !committed.ids().contains(item.id())).toList();
            if (reached) {
                break;
            }
        }
        List<UnpackedItem> unpacked = reasons.explainAll(remaining, containers, reached, packed, config);
        // Optimality may be claimed only when an exhaustive search filled a single container, the
        // cheapest, since containers are tried in cost order.
        boolean proven = exhaustive && !reached && unpacked.isEmpty() && packed.size() == 1;
        return new Outcome(packed, unpacked, proven, reached, dominantLattice);
    }

    /**
     * The greedy round's key for one trial, read from the container the trial built: a
     * made-to-measure trial carries its fabricated box, so the key prices the box that ships,
     * never the machine maximum the loop declared.
     */
    private SortKey roundScore(SingleContainerSolution one, int remainingCount, PackingConfig config) {
        Container container = one.state().container;
        boolean billed = config.objective().equals("shipping_cost")
                || config.objective().equals(LandedCostSolutionScorer.OBJECTIVE_NAME);
        if (!billed || config.dimensionalWeightDivisor().isEmpty()) {
            if (config.objective().equals("maximum_volume") && selector instanceof DefaultContainerSelector) {
                // The round keeps the trial that loaded the most volume, not the one that seated
                // the most pieces. A caller's own selector is left alone.
                BigInteger used = one.state().usedVolume;
                return SortKey.of(used.negate(), container.costMinor(),
                        container.innerDimensions().volume().subtract(used), container.id());
            }
            return selector.score(container, one);
        }
        long billedTicks = ShippingCostSolutionScorer.billedTicks(container, one.state().billedDimensions(),
                one.state().payloadTicks, config);
        long placed = one.state().placementCount();
        if (config.objective().equals("shipping_cost")) {
            return SortKey.of(-placed, billedTicks, container.id());
        }
        // Landed cost ranks the round by the money the finished score will charge: this trial is
        // committed verbatim, so its billed weight is final. An unpriceable trial is unshippable,
        // not merely dear, so it sorts behind every priceable one.
        long charge = LandedCostSolutionScorer.chargeOrUnpriceable(container, billedTicks);
        long perContainer = Math.max(placed, 1);
        return SortKey.of(charge, (remainingCount + perContainer - 1) / perContainer, -placed, container.id());
    }

    /** One container's trial for a round, and how many single-container solves it spent. */
    private record Solved(SingleContainerSolution solution, long solves) {
    }

    /**
     * A declared container is one solve; a made-to-measure one runs the rung chain and returns
     * its trial already fabricated.
     */
    private Solved trial(SingleContainerSolver solver, Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        if (container.madeToMeasure().isEmpty()) {
            return new Solved(solver.packOne(container, sequence, items, config, stats, deadline), 1);
        }
        return madeToMeasureTrial(solver, container, sequence, items, config, stats, deadline);
    }

    /**
     * Try each solving envelope in ascending order and keep the first that places every item;
     * failing that, the trial this round's own key prefers, the smaller rung on a tie. At most four
     * solves, two trial states alive. A compact lattice cannot describe a box cut to the achieved
     * extent, so the rung solves always report coordinates, and a rung choice is a heuristic, so
     * the trial is never exhaustive.
     */
    private Solved madeToMeasureTrial(SingleContainerSolver solver, Container container, long sequence,
            List<ItemInstance> items, PackingConfig config, SearchStats stats, Deadline deadline) {
        MadeToMeasure machine = container.madeToMeasure().orElseThrow();
        List<Sides> rungs = machine.solvingEnvelopes(Sides.of(container.innerDimensions()),
                MadeToMeasure.instanceEdges(items, config.clearance().ticks()));
        PackingConfig rungConfig = config.requirePlacementCoordinates() ? config : config.withPlacementCoordinates();
        SingleContainerSolution best = null;
        SortKey bestKey = null;
        SingleContainerSolution last = null;
        boolean stopped = false;
        long solves = 0;
        for (Sides rung : rungs) {
            SingleContainerSolution one;
            try {
                one = solver.packOne(container.withEnvelope(rung), sequence, items, rungConfig, stats, deadline);
            } catch (TimeLimitReached cut) {
                if (best == null) {
                    throw cut;
                }
                stopped = true;
                break;
            }
            solves++;
            stopped = one.timeLimitReached();
            last = one;
            if (one.state().placementCount() > 0) {
                SingleContainerSolution fabricated = fabricated(container, one);
                if (one.state().placementCount() == items.size()) {
                    return new Solved(fabricated, solves);
                }
                SortKey key = roundScore(fabricated, items.size(), config);
                if (best == null || key.compareTo(bestKey) < 0) {
                    best = fabricated;
                    bestKey = key;
                }
            }
            if (stopped || deadline.expired()) {
                stopped = true;
                break;
            }
        }
        SingleContainerSolution chosen = best != null ? best : last;
        return new Solved(new SingleContainerSolution(chosen.state(), chosen.unpacked(), chosen.exhaustive(),
                chosen.timeLimitReached() || stopped, chosen.dominantLattice()), solves);
    }

    /** A rung trial as the box cut for it: the achieved extent of its placements on the grid. O(p). */
    private static SingleContainerSolution fabricated(Container declared, SingleContainerSolution one) {
        Container box = declared.fabricate(FlatBag.extentOf(one.state().placements));
        return new SingleContainerSolution(one.state().withContainer(box), one.unpacked(), false,
                one.timeLimitReached(), false);
    }

    private record Trial(SortKey score, SingleContainerSolution solution) {
    }

    record Committed(PackedContainer packed, Set<String> ids) {
    }

    static Committed commit(ContainerState state) {
        Set<String> ids = new HashSet<>();
        // A result carries the request's container, not the seeded instance: anything that
        // rebuilds a state from a packed container re-adds every placement itself, and a seeded
        // container would add the fixed ones twice.
        Container container = state.container.preloaded().isEmpty()
                ? state.container
                : state.container.withPreloaded(List.of());
        if (state.latticeSummary != null) {
            state.latticeItems.forEach(item -> ids.add(item.id()));
            return new Committed(new PackedContainer(container, state.sequence, List.of(),
                    Optional.of(state.latticeSummary), state.latticeItems), ids);
        }
        state.placements.forEach(placement -> ids.add(placement.instance().id()));
        List<Placement> withLoads = TopLoadAssigner.assign(state.placements);
        return new Committed(new PackedContainer(container, state.sequence, withLoads, Optional.empty(),
                List.of()), ids);
    }

    /**
     * A partial container plan in the beam; inventory null means unlimited. Containers are
     * numbered per type, as the greedy path and every other engine number them: a fixed placement
     * names {@code <type>#<n>}, so the count must be the type's own.
     */
    record Plan(List<PackedContainer> packed, List<ItemInstance> remaining, Map<String, Long> inventory,
            Map<String, Long> sequences, boolean exhaustive, boolean dominant) {
    }

    /**
     * Beam over whole container plans. Each level expands every plan by every container type,
     * keeps the best per (remaining items, inventory) signature, and ranks by an admissible
     * bound. O(levels * width * c * S) with the node limit as a hard cap.
     */
    Outcome plans(SingleContainerSolver solver, List<ItemInstance> items, List<Container> containers,
            PackingConfig config, SearchStats stats, Deadline deadline, Opened opened) {
        Plan initial = opened.plan();
        boolean reached = opened.reached();
        List<Plan> beam = reached ? List.of() : List.of(initial);
        Plan incumbent = initial;
        long nodes = 0;
        long maximum = maximumContainers(containers, items, config);
        List<Container> ordered = ordered(containers);
        while (!beam.isEmpty() && nodes < config.containerPlanNodeLimit()) {
            List<Plan> expansions = new ArrayList<>();
            for (Plan plan : beam) {
                if (plan.remaining().isEmpty() || plan.packed().size() >= maximum) {
                    // Already weighed against the incumbent when it was created (below), and the
                    // incumbent only ever improves, so it cannot win now.
                    continue;
                }
                for (Container container : ordered) {
                    if (nodes >= config.containerPlanNodeLimit()) {
                        break;
                    }
                    if (deadline.expired()) {
                        reached = true;
                        break;
                    }
                    Long left = plan.inventory().get(container.id());
                    if (left != null && left <= 0) {
                        continue;
                    }
                    SingleContainerSolution one;
                    try {
                        Solved solved = trial(solver, container, plan.sequences().get(container.id()) + 1,
                                plan.remaining(), config, stats, deadline);
                        one = solved.solution();
                        // Each rung is one single-container solve, so the node limit keeps bounding
                        // the work of a made-to-measure chain.
                        nodes += solved.solves();
                    } catch (TimeLimitReached cut) {
                        nodes++;
                        reached = true;
                        break;
                    }
                    reached = reached || one.timeLimitReached();
                    if (one.state().placementCount() == 0) {
                        continue;
                    }
                    Plan child = extend(plan, container, one);
                    expansions.add(child);
                    if (better(child, incumbent, config)) {
                        incumbent = child;
                    }
                    if (reached) {
                        break;
                    }
                }
                if (reached) {
                    break;
                }
            }
            if (reached || expansions.isEmpty()) {
                break;
            }
            beam = nextBeam(expansions, containers, config);
        }
        List<UnpackedItem> unpacked = reasons.explainAll(incumbent.remaining(), containers, reached,
                incumbent.packed(), config);
        boolean proven = incumbent.exhaustive() && !reached && unpacked.isEmpty() && incumbent.packed().size() == 1;
        return new Outcome(incumbent.packed(), unpacked, proven, reached, incumbent.dominant());
    }

    private static Plan extend(Plan plan, Container container, SingleContainerSolution one) {
        Committed committed = commit(one.state());
        List<PackedContainer> packed = new ArrayList<>(plan.packed());
        packed.add(committed.packed());
        List<ItemInstance> remaining = plan.remaining().stream()
                .filter(item -> !committed.ids().contains(item.id())).toList();
        Map<String, Long> inventory = new LinkedHashMap<>(plan.inventory());
        if (inventory.get(container.id()) != null) {
            inventory.merge(container.id(), -1L, Long::sum);
        }
        Map<String, Long> sequences = new LinkedHashMap<>(plan.sequences());
        sequences.merge(container.id(), 1L, Long::sum);
        return new Plan(packed, remaining, inventory, sequences, plan.exhaustive() && one.exhaustive(),
                plan.dominant() && one.dominantLattice());
    }

    private static List<Plan> nextBeam(List<Plan> expansions, List<Container> containers, PackingConfig config) {
        Map<String, Plan> dominant = new LinkedHashMap<>();
        for (Plan plan : expansions) {
            // The inventory keys are the same for every plan, so their values alone identify it.
            String signature = signature(plan.remaining()) + "::" + plan.inventory().values();
            Plan previous = dominant.get(signature);
            if (previous == null || planScore(plan, List.of(), config).compareTo(planScore(previous, List.of(), config)) < 0) {
                dominant.put(signature, plan);
            }
        }
        List<Plan> ranked = StableSorter.sortBy(new ArrayList<>(dominant.values()),
                plan -> planBound(plan, containers, config));
        return new ArrayList<>(ranked.subList(0, (int) Math.min(config.containerPlanBeamWidth(), ranked.size())));
    }

    private static boolean better(Plan candidate, Plan incumbent, PackingConfig config) {
        return planScore(candidate, candidate.remaining(), config)
                .compareTo(planScore(incumbent, incumbent.remaining(), config)) < 0;
    }

    private static ObjectiveScore planScore(Plan plan, List<ItemInstance> remaining, PackingConfig config) {
        List<UnpackedItem> unpacked = remaining.stream()
                .map(item -> new UnpackedItem(item, "search_exhausted", List.of())).toList();
        RawSolution raw = new RawSolution("container_plan", plan.packed(), unpacked, new SearchStats(), false, false,
                false);
        return ObjectiveRegistry.resolve(config.objective(), config).score(raw);
    }

    /** A lower bound on any completion of the plan, then the remaining signature as tie-break. */
    private static SortKey planBound(Plan plan, List<Container> containers, PackingConfig config) {
        List<Long> score = new ArrayList<>(planScore(plan, List.of(), config).components());
        int index = config.objective().equals("default") ? 1 : 2;
        score.set(index, score.get(index) + additionalContainerLowerBound(plan, containers));
        List<Object> key = new ArrayList<>(score);
        key.add(signature(plan.remaining()));
        return new SortKey(key);
    }

    private static long additionalContainerLowerBound(Plan plan, List<Container> containers) {
        List<Container> available = containers.stream().filter(container -> {
            Long left = plan.inventory().get(container.id());
            return left == null || left > 0;
        }).toList();
        if (plan.remaining().isEmpty() || available.isEmpty()) {
            return 0;
        }
        long lower = 0;
        if (plan.remaining().stream().noneMatch(item -> item.item().nestingHeight().isPresent())) {
            BigInteger capacity = available.stream().map(container -> container.innerDimensions().volume())
                    .reduce(BigInteger.ZERO, BigInteger::max);
            if (capacity.signum() > 0) {
                // A hull counts its own solid, as the beam and exact floors do.
                BigInteger required = plan.remaining().stream().map(Nesting::itemOccupiedVolume)
                        .reduce(BigInteger.ZERO, BigInteger::add);
                lower = Math.max(lower, required.add(capacity).subtract(BigInteger.ONE).divide(capacity).longValueExact());
            }
        }
        if (available.stream().allMatch(container -> container.maxPayload().isPresent())) {
            long capacity = available.stream().mapToLong(container -> container.maxPayload().get().ticks()).max()
                    .orElse(0);
            if (capacity > 0) {
                long required = plan.remaining().stream().mapToLong(item -> item.weight().ticks()).sum();
                lower = Math.max(lower, (required + capacity - 1) / capacity);
            }
        }
        return lower;
    }

    private static String signature(List<ItemInstance> items) {
        return items.stream().map(ItemInstance::id).collect(Collectors.joining("|"));
    }
}
