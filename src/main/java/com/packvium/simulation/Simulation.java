package com.packvium.simulation;

import com.packvium.pareto.CandidateResult;
import com.packvium.pareto.Pareto;
import com.packvium.pareto.ProfileReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scenario simulation and what-if comparison.
 *
 * <p>A scenario runs a fixed order corpus against one {@link ScenarioVersionPin} and records
 * what happened, order by order, without mutating any registry. Two scenarios compare only when
 * they ran the identical corpus, and the comparison is a Pareto report per order, never a single
 * blended number.
 *
 * <p>Nothing here prices, validates or packs. {@link #runScenario} takes an injected
 * {@link OrderEvaluator} that calls the catalog, rating and policy registries and the packer, so
 * the orchestration stays decoupled from what it orchestrates. Given a deterministic evaluator,
 * a scenario is reproducible from its recorded pin and order ids alone.
 */
public final class Simulation {
    private static final String BASELINE = "baseline";
    private static final String TREATMENT = "treatment";

    private Simulation() {
    }

    /**
     * Run every order through {@code evaluator} under {@code versionPin}, in order. Called twice
     * with the same arguments and a deterministic evaluator, it returns equal results. O(o)
     * evaluator calls.
     */
    public static ScenarioResult runScenario(String scenarioId, ScenarioVersionPin versionPin, List<String> orderIds,
            OrderEvaluator evaluator) {
        List<OrderRunResult> runs = new ArrayList<>(orderIds.size());
        for (String orderId : orderIds) {
            runs.add(evaluator.evaluate(orderId, versionPin));
        }
        return new ScenarioResult(scenarioId, versionPin, orderIds, runs);
    }

    /**
     * Compare two scenarios order by order. Each order becomes its own profile, so a caller sees
     * exactly which orders improved, regressed or traded off; {@code baseline} and
     * {@code treatment} are the two engines compared within it. An order an arm failed on has no
     * metrics and is left out of that order's report; an order both arms failed on gets none.
     * O(o) here, plus {@link Pareto#generateReport} over at most two candidates per order.
     *
     * @throws MismatchedOrderCorpusError if the scenarios did not run the same orders in the same order
     */
    public static List<ProfileReport> compareScenarios(ScenarioResult baseline, ScenarioResult treatment,
            Map<String, Boolean> higherIsBetter) {
        requireSameCorpus(baseline, treatment);
        List<CandidateResult> candidates = new ArrayList<>();
        // ScenarioResult proves runs match order_ids one to one and the check above proves both
        // arms share them, so the runs pair up positionally in one pass.
        for (int index = 0; index < baseline.runs().size(); index++) {
            OrderRunResult baseRun = baseline.runs().get(index);
            OrderRunResult treatRun = treatment.runs().get(index);
            if (baseRun.succeeded()) {
                candidates.add(new CandidateResult(baseRun.orderId(), BASELINE, baseRun.metrics()));
            }
            if (treatRun.succeeded()) {
                candidates.add(new CandidateResult(baseRun.orderId(), TREATMENT, treatRun.metrics()));
            }
        }
        return Pareto.generateReport(candidates, higherIsBetter);
    }

    /**
     * Refuses two scenarios over different corpora, shared by every paired comparison.
     *
     * @throws MismatchedOrderCorpusError if the order ids differ in content or sequence
     */
    public static void requireSameCorpus(ScenarioResult baseline, ScenarioResult treatment) {
        if (!baseline.orderIds().equals(treatment.orderIds())) {
            throw new MismatchedOrderCorpusError(
                    "baseline and treatment must have run the identical order corpus, in the same order");
        }
    }
}
