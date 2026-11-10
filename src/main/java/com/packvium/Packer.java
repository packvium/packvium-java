package com.packvium;

import com.packvium.algorithm.Deadline;
import com.packvium.algorithm.PortfolioRun;
import com.packvium.algorithm.RawSolution;
import com.packvium.algorithm.SolverOrchestrator;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.UnratedWeightException;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.objective.LandedCostSolutionScorer;
import com.packvium.objective.ObjectiveRegistry;
import com.packvium.objective.SolutionScorer;
import com.packvium.objective.UnknownObjectiveException;
import com.packvium.result.AlgorithmReport;
import com.packvium.result.PackingResult;
import com.packvium.result.PackingStatus;
import com.packvium.result.ResultFact;
import com.packvium.result.StartRecord;
import com.packvium.result.TerminationSummary;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import com.packvium.validation.IndependentSolutionValidator;
import com.packvium.validation.ValidationIssue;
import com.packvium.validation.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Solves a request: runs the portfolio, validates and scores every answer, returns the best. */
public final class Packer {
    private final PackingConfig config;
    private final ExtensionRegistry extensions;
    private final Optional<SolutionScorer> solutionScorer;
    private final LongSupplier clock;

    /** No caller extensions, as PHP's {@code new Packer($config)} defaults to an empty registry. */
    public Packer(PackingConfig config) {
        this(config, ExtensionRegistry.none());
    }

    public Packer(PackingConfig config, ExtensionRegistry extensions) {
        this(config, extensions, Optional.empty(), System::nanoTime);
    }

    public Packer(PackingConfig config, ExtensionRegistry extensions, Optional<SolutionScorer> solutionScorer,
            LongSupplier clock) {
        this.config = config;
        this.extensions = extensions;
        this.solutionScorer = solutionScorer;
        this.clock = clock;
    }

    public PackingResult pack(List<Item> items, List<Container> containers) {
        PackingRequest request = new PackingRequest(items, containers);
        refuseUnpriceableObjectives(request);
        Deadline deadline = Deadline.ofMilliseconds(config.timeLimitMs(), clock);
        SolverOrchestrator orchestrator = new SolverOrchestrator(extensions.placementConstraints(),
                extensions.itemOrderStrategies(), extensions.solvers(), extensions.candidateScorer(),
                extensions.containerSelector());
        PortfolioRun portfolio = orchestrator.solvePortfolio(request.instances(), request.containers(), config,
                deadline);
        SolutionScorer scorer = solutionScorer.orElseGet(() -> ObjectiveRegistry.resolve(config.objective(), config));
        List<PackingResult> ranked = new ArrayList<>();
        for (RawSolution raw : portfolio.solutions()) {
            ranked.add(rank(raw, request, portfolio.starts(), scorer, deadline));
        }
        ranked = StableSorter.sortBy(ranked, result -> SortKey.of(
                result.status() == PackingStatus.INVALID_RESULT ? 1L : 0L,
                new SortKey(List.copyOf(result.score())), result.algorithm().solver()));
        List<PackingResult> valid = ranked.stream().filter(result -> result.status() != PackingStatus.INVALID_RESULT)
                .toList();
        List<PackingResult> selected = valid.isEmpty() ? ranked : valid;
        PackingResult best = selected.get(0);
        refuseUnpriceableAnswer(best.containers());
        // The unpriceable sentinel is a search device, never an answer, for runner-ups too: filter
        // before slicing to top-k.
        List<PackingResult> alternatives = selected.subList(1, selected.size()).stream()
                .filter(result -> LandedCostSolutionScorer.unpriceableContainer(result.containers(), config).isEmpty())
                .limit(Math.max(0, config.topK() - 1)).toList();
        return best.withAlternatives(alternatives);
    }

    /**
     * Both cost objectives price billed weight, so both need the divisor; and landed cost needs a
     * tariff on every container. Both are static properties of the request, so they are refused
     * before any solver runs. There is no library-chosen default divisor.
     */
    private void refuseUnpriceableObjectives(PackingRequest request) {
        boolean priced = config.objective().equals("shipping_cost")
                || config.objective().equals(LandedCostSolutionScorer.OBJECTIVE_NAME);
        if (priced && config.dimensionalWeightDivisor().isEmpty()) {
            throw new UnknownObjectiveException(
                    "the " + config.objective() + " objective requires configuration.dimensional_weight_divisor");
        }
        if (!config.objective().equals(LandedCostSolutionScorer.OBJECTIVE_NAME)) {
            return;
        }
        for (Container container : request.containers()) {
            if (container.rateTable().isEmpty()) {
                throw new UnknownObjectiveException("the lowest_landed_cost objective requires a rate_table on every "
                        + "container; '" + container.id() + "' has none");
            }
        }
    }

    /**
     * Returning a packing the tariff cannot price would quote a number the carrier never
     * published. The refusal lives here, not in the scorer: throwing while comparing candidates
     * would abort runs that have a shippable answer.
     */
    private void refuseUnpriceableAnswer(List<PackedContainer> containers) {
        LandedCostSolutionScorer.unpriceableContainer(containers, config).ifPresent(unpriceable -> {
            throw new UnratedWeightException("container '" + unpriceable.containerId() + "' bills at "
                    + unpriceable.billedGrams() + " g, above its rate table's last bracket ("
                    + unpriceable.lastBracketG() + " g); the shipment has no published price");
        });
    }

    private PackingResult rank(RawSolution raw, PackingRequest request, List<StartRecord> records,
            SolutionScorer scorer, Deadline deadline) {
        List<Long> score = scorer.score(raw).components();
        PackingStatus status = status(raw);
        List<String> warnings = new ArrayList<>();
        if (config.validateResult()) {
            ValidationReport report = validate(raw, request);
            if (!report.valid()) {
                status = PackingStatus.INVALID_RESULT;
                for (ValidationIssue issue : report.issues()) {
                    warnings.add(issue.code() + ": " + issue.detail());
                }
            }
        }
        ResultFact termination = TerminationSummary.aggregate(markWinner(records, raw.solverName()),
                status == PackingStatus.INVALID_RESULT);
        if (raw.effortLimitReached() && !raw.timeLimitReached()) {
            termination = termination.withCode("effort_limit");
        }
        boolean aggregateTimedOut = Boolean.TRUE.equals(termination.attributes().get("global_deadline_reached"))
                || raw.timeLimitReached();
        AlgorithmReport report = new AlgorithmReport(config.profile().wireName(), raw.solverName(),
                deadline.elapsedMs(), config.seed(), aggregateTimedOut, raw.effortLimitReached(),
                raw.stats().candidatesEvaluated, raw.stats().placementsAttempted, raw.stats().metrics());
        return PackingResult.of(status, raw.containers(), raw.unpacked(), report, score, warnings, termination,
                config.objective());
    }

    private static PackingStatus status(RawSolution raw) {
        if (!raw.unpacked().isEmpty()) {
            return raw.timeLimitReached() ? PackingStatus.TIME_LIMIT : PackingStatus.BEST_FOUND;
        }
        return raw.exhaustive() ? PackingStatus.OPTIMAL : PackingStatus.FEASIBLE;
    }

    /**
     * Validation re-derives every guarantee from per-item placements, so a compact lattice
     * container is expanded just for the check; the returned result stays compact.
     */
    private ValidationReport validate(RawSolution raw, PackingRequest request) {
        List<PackedContainer> expanded = raw.containers().stream()
                .map(container -> container.latticeSummary().isPresent()
                        ? new PackedContainer(container.container(), container.sequence(),
                                container.expandPlacements(), Optional.empty(), List.of())
                        : container)
                .toList();
        return new IndependentSolutionValidator().validate(request, expanded, config.minimumSupportRatio(),
                config.clearance(), raw.unpacked());
    }

    /** The first start record carrying the winner's id is the selected one. */
    private static List<StartRecord> markWinner(List<StartRecord> records, String solverName) {
        List<StartRecord> marked = new ArrayList<>(records.size());
        boolean winnerMarked = false;
        for (StartRecord record : records) {
            boolean selected = !winnerMarked && record.id().equals(solverName);
            winnerMarked = winnerMarked || selected;
            marked.add(record.withSelected(selected));
        }
        return marked;
    }
}
