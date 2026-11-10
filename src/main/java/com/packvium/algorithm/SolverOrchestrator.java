package com.packvium.algorithm;

import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.UnpackedItem;
import com.packvium.extension.CandidateScorer;
import com.packvium.extension.ContainerSelector;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.extension.DefaultContainerSelector;
import com.packvium.extension.ItemOrderStrategy;
import com.packvium.result.StartRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the portfolio: every solver over every distinct item order, each start on its own slice
 * of the deadline, sequentially and in a fixed order, so the ranked answer never depends on
 * scheduling.
 */
public final class SolverOrchestrator {
    private static final Set<String> SOLVER_NAMES = new LinkedHashSet<>(
            List.of("grid", "extreme_points", "homogeneous_blocks", "layer", "maximal_spaces", "exact_small"));

    private final List<PlacementConstraint> customConstraints;
    private final List<ItemOrderStrategy> customOrders;
    private final List<SingleContainerSolver> customSolvers;
    private final Optional<CandidateScorer> customScorer;
    private final Optional<ContainerSelector> customSelector;
    private final CandidateScorer scorer;
    private final UnpackedReasons reasons;

    public SolverOrchestrator(List<PlacementConstraint> customConstraints, List<ItemOrderStrategy> customOrders,
            List<SingleContainerSolver> customSolvers, Optional<CandidateScorer> candidateScorer,
            Optional<ContainerSelector> containerSelector) {
        this.customConstraints = List.copyOf(customConstraints);
        this.customOrders = List.copyOf(customOrders);
        this.customSolvers = List.copyOf(customSolvers);
        this.customScorer = candidateScorer;
        this.customSelector = containerSelector;
        this.scorer = candidateScorer.orElseGet(DefaultCandidateScorer::new);
        this.reasons = new UnpackedReasons(this.customConstraints, scorer);
    }

    public PortfolioRun solvePortfolio(List<ItemInstance> items, List<Container> containers, PackingConfig config,
            Deadline deadline) {
        List<Start> starts = starts(items, config);
        Optional<EffortBudget> budget = config.effortBudget();
        if (budget.isPresent() && budget.get().maxRestarts().isPresent()) {
            starts = starts.subList(0, (int) Math.min(budget.get().maxRestarts().get(), starts.size()));
        }
        return solveSequential(items, containers, config, deadline, starts, budget);
    }

    private PortfolioRun solveSequential(List<ItemInstance> items, List<Container> containers, PackingConfig config,
            Deadline deadline, List<Start> starts, Optional<EffortBudget> effortBudget) {
        List<RawSolution> results = new ArrayList<>();
        List<StartRecord> records = new ArrayList<>();
        starts.forEach(start -> records.add(new StartRecord(start.id(), false, false, false, false, false)));
        for (int position = 0; position < starts.size(); position++) {
            if (deadline.expired()) {
                break;
            }
            Start start = starts.get(position);
            SearchStats stats = new SearchStats();
            Deadline slice = deadline.slice(starts.size() - position);
            slice = effortBudget.isPresent() ? slice.withEffort(effortBudget.get(), stats) : slice;
            PackingConfig startConfig = start.orderName().endsWith(":greedy") ? greedyConfig(config) : config;
            ContainerPlanner.Outcome outcome = acrossContainers(start.solver(), start.order(), containers, startConfig,
                    stats, slice);
            boolean effortReached = slice.effortExceeded();
            boolean wallReached = outcome.reached() && slice.remainingNs() <= 0;
            List<UnpackedItem> unpacked = effortReached ? relabelEffort(outcome.unpacked()) : outcome.unpacked();
            results.add(new RawSolution(start.id(), outcome.packed(), unpacked, stats, wallReached, effortReached,
                    outcome.proven()));
            records.set(position, new StartRecord(start.id(), true, !outcome.reached(), outcome.reached(), false,
                    false));
            // A single type's densest packing is the lattice just tried: once it placed everything,
            // no default-scored start can do better, so the rest would only spend the deadline. That
            // holds only when the lattice arithmetic really ran, and never for a custom
            // scorer, which asks for a placement behaviour the lattice cannot honour.
            if (outcome.dominantLattice() && unpacked.isEmpty() && customScorer.isEmpty()) {
                break;
            }
        }
        return finishPortfolio(items, containers, deadline, results, records);
    }

    private static List<UnpackedItem> relabelEffort(List<UnpackedItem> unpacked) {
        return unpacked.stream().map(item -> item.reason().equals("time_limit")
                ? new UnpackedItem(item.instance(), "effort_limit", item.details())
                : item).toList();
    }

    private PortfolioRun finishPortfolio(List<ItemInstance> items, List<Container> containers, Deadline deadline,
            List<RawSolution> results, List<StartRecord> records) {
        boolean globalDeadline = deadline.expired();
        List<StartRecord> finished = new ArrayList<>(records.stream()
                .map(record -> record.withGlobalDeadline(globalDeadline)).toList());
        List<RawSolution> solutions = new ArrayList<>(results);
        if (solutions.isEmpty()) {
            String fallbackId = "portfolio:fallback";
            List<UnpackedItem> unpacked = items.stream().map(item -> {
                UnpackedReasons.Reason reason = reasons.reason(item, containers, true);
                return new UnpackedItem(item, reason.code(), reason.details());
            }).toList();
            solutions.add(new RawSolution(fallbackId, List.of(), unpacked, new SearchStats(), true, false, false));
            finished.add(new StartRecord(fallbackId, true, true, false, false, globalDeadline));
        }
        return new PortfolioRun(solutions, finished);
    }

    private List<Start> starts(List<ItemInstance> items, PackingConfig config) {
        List<ItemOrders.Named> orders = ItemOrders.orders(items, config, customOrders);
        List<Start> starts = new ArrayList<>();
        for (SingleContainerSolver solver : solvers(items, config)) {
            // A lattice ignores item order, so repeating it per ordering only burns budget.
            List<ItemOrders.Named> chosen = solver.orderInsensitive() ? orders.subList(0, Math.min(1, orders.size()))
                    : orders;
            chosen.forEach(order -> starts.add(new Start(solver, order.name(), order.order())));
        }
        if (config.containerPlanBeamWidth() <= 1 || config.profile() != SolverProfile.QUALITY) {
            return starts;
        }
        List<Start> withGreedy = new ArrayList<>();
        for (Start start : starts) {
            withGreedy.add(start.solver().name().equals("homogeneous_blocks")
                    ? start
                    : new Start(start.solver(), start.orderName() + ":greedy", start.order()));
        }
        int refinements = 0;
        for (Start start : starts) {
            if (start.solver().name().equals("extreme_points") && refinements < 2) {
                withGreedy.add(new Start(start.solver(), start.orderName() + ":beam", start.order()));
                refinements++;
            }
        }
        return withGreedy;
    }

    /** The same config with the beams switched off, for the cheap greedy pass of a quality start. */
    private static PackingConfig greedyConfig(PackingConfig config) {
        return new PackingConfig(config.profile(), config.timeLimitMs(), config.topK(), config.seed(),
                config.maxContainers(), config.clearance(), config.minimumSupportRatio(), config.exactItemLimit(),
                config.multiStartOrders(), config.validateResult(), 1, config.maxCandidatePoints(), config.solvers(),
                config.objective(), config.effortBudget(), config.dimensionalWeightDivisor(),
                config.dimensionalWeightLengthUnit(), config.dimensionalWeightWeightUnit(),
                config.requirePlacementCoordinates(), 1, 1);
    }

    private List<SingleContainerSolver> solvers(List<ItemInstance> items, PackingConfig config) {
        List<SingleContainerSolver> base = config.solvers().isEmpty() ? defaultSolvers(items, config)
                : namedSolvers(config.solvers());
        List<SingleContainerSolver> all = new ArrayList<>(customSolvers);
        all.addAll(base);
        return all;
    }

    private List<SingleContainerSolver> namedSolvers(List<String> names) {
        List<String> unknown = names.stream().filter(name -> !SOLVER_NAMES.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownSolverException("unknown solver name(s) " + String.join(", ", unknown)
                    + "; expected one of " + String.join(", ", SOLVER_NAMES));
        }
        return names.stream().map(this::solver).toList();
    }

    private SingleContainerSolver solver(String name) {
        return switch (name) {
            case "grid" -> new GridSolver();
            case "extreme_points" -> new ExtremePointSolver(customConstraints, scorer);
            case "homogeneous_blocks" -> new HomogeneousBlockSolver(customConstraints);
            case "layer" -> new LayerSolver(customConstraints, scorer);
            case "maximal_spaces" -> new MaximalSpaceSolver(customConstraints, scorer);
            case "exact_small" -> new ExactSmallSolver(customConstraints, scorer);
            default -> throw new UnknownSolverException("unknown solver name(s) " + name);
        };
    }

    private List<SingleContainerSolver> defaultSolvers(List<ItemInstance> items, PackingConfig config) {
        GridSolver grid = new GridSolver();
        if (grid.supports(items) && customConstraints.isEmpty()) {
            return config.profile() == SolverProfile.FAST ? List.of(grid)
                    : List.of(grid, new ExtremePointSolver(List.of(), scorer));
        }
        if (config.profile() == SolverProfile.FAST) {
            return List.of(new LayerSolver(customConstraints, scorer));
        }
        if (config.profile() == SolverProfile.EXACT_SMALL && items.size() <= config.exactItemLimit()) {
            return List.of(new ExactSmallSolver(customConstraints, scorer),
                    new ExtremePointSolver(customConstraints, scorer));
        }
        if (config.profile() == SolverProfile.QUALITY) {
            List<SingleContainerSolver> quality = new ArrayList<>();
            if (items.size() <= config.exactItemLimit()) {
                quality.add(new ExactSmallSolver(customConstraints, scorer));
            }
            quality.addAll(List.of(new HomogeneousBlockSolver(customConstraints),
                    new ExtremePointSolver(customConstraints, scorer), new MaximalSpaceSolver(customConstraints, scorer),
                    new LayerSolver(customConstraints, scorer)));
            return quality;
        }
        return List.of(new ExtremePointSolver(customConstraints, scorer), new LayerSolver(customConstraints, scorer));
    }

    /**
     * The request-level lower bound, computed once at the root of an exact_small or plan-beam
     * start, for the default objective only. Nothing downstream reads it yet.
     */
    private static void recordRootBound(SingleContainerSolver solver, boolean beam, List<ItemInstance> items,
            List<Container> containers, PackingConfig config, SearchStats stats) {
        if (stats.objectiveLowerBound().isPresent()) {
            return;
        }
        if (!beam && !solver.name().equals("exact_small")) {
            return;
        }
        if (!config.objective().equals("default")) {
            return;
        }
        stats.setObjectiveLowerBound(ObjectiveBounds.compute(items, containers));
    }

    private ContainerPlanner.Outcome acrossContainers(SingleContainerSolver solver, List<ItemInstance> items,
            List<Container> containers, PackingConfig config, SearchStats stats, Deadline deadline) {
        boolean beam = config.containerPlanBeamWidth() > 1
                && customSelector.map(selector -> selector instanceof DefaultContainerSelector).orElse(true);
        recordRootBound(solver, beam, items, containers, config, stats);
        ContainerPlanner planner = new ContainerPlanner(customSelector.orElseGet(DefaultContainerSelector::new),
                reasons);
        return beam
                ? planner.plans(solver, items, containers, config, stats, deadline)
                : planner.greedy(solver, items, containers, config, stats, deadline);
    }
}
