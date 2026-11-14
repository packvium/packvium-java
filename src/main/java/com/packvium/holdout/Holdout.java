package com.packvium.holdout;

import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import com.packvium.pareto.ProfileReport;
import com.packvium.recommendations.Recommendation;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioResult;
import com.packvium.simulation.ScenarioVersionPin;
import com.packvium.simulation.Simulation;
import com.packvium.support.PythonRepr;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Historical replay and holdout evaluation for a recommendation: would it have improved past
 * decisions, scored on a slice it was not derived from?
 *
 * <p>Three rules shape it, and all three are the difference between a backtest and a story told
 * about one.
 *
 * <p><strong>The split is by time.</strong> A decision is held out when its earliest recorded
 * event is at or after {@code splitAt}. A recommendation whose supporting orders reach into the
 * holdout is refused rather than scored: it would be citing its own answer sheet.
 *
 * <p><strong>Most of the ledger cannot be scored counterfactually.</strong> Damage, returns,
 * repacks and operator overrides were realised under the carton that actually shipped; nothing
 * says what would have happened in another one. They are reported against the baseline as
 * negative evidence and never credited to the treatment.
 *
 * <p><strong>The validator runs before any metric is compared, and its verdict is not an input to
 * a score.</strong> An arm the validator rejects has failed that decision, whatever it cost, and
 * the validator is handed no score.
 *
 * <p>The aggregate is a count of decisions, never a mean delta: a mean is one number a few large
 * orders can carry by themselves.
 */
public final class Holdout {
    /**
     * Ledger facts about something a person or the world did to the carton that shipped. Under a
     * different carton they may not have happened, so they are never credited to the treatment.
     */
    public static final Set<OutcomeEventType> NOT_COUNTERFACTUAL = Set.copyOf(EnumSet.of(
            OutcomeEventType.DAMAGE, OutcomeEventType.RETURN, OutcomeEventType.REPACK,
            OutcomeEventType.OPERATOR_OVERRIDE));

    private static final String BASELINE = "baseline";
    private static final String TREATMENT = "treatment";

    private Holdout() {
    }

    /**
     * Score {@code recommendation} against the decisions {@code splitAt} holds out.
     *
     * <p>{@code splitAt} is required and has no default: any default would be this library making
     * a claim about how much history is enough for a business it cannot see. O(d · e) to split d
     * decisions of at most e events, plus two evaluator and two validator calls per held-out
     * decision.
     *
     * @param decisionIds the decisions to split, each with at least one recorded event
     * @param higherIsBetter the direction of every compared metric axis
     * @param splitAt the first instant of the holdout, in the ledger's epoch milliseconds
     * @throws SupportingEvidenceInHoldoutError if the recommendation cites a held-out decision
     * @throws IllegalArgumentException if {@code splitAt} is negative, no decision is given, or a
     *     decision has no recorded events and so cannot be placed on either side of a split by time
     */
    public static HoldoutEvaluation evaluateOnHistory(Recommendation recommendation, OutcomeLedger ledger,
            List<String> decisionIds, ScenarioVersionPin baselinePin, ScenarioVersionPin treatmentPin,
            HoldoutEvaluator evaluator, IndependentValidator validator, Map<String, Boolean> higherIsBetter,
            long splitAt) {
        if (splitAt < 0) {
            throw new IllegalArgumentException("split_at cannot be negative");
        }
        if (decisionIds.isEmpty()) {
            throw new IllegalArgumentException("at least one decision id is required");
        }

        List<String> training = new ArrayList<>();
        List<String> holdout = new ArrayList<>();
        for (String decisionId : decisionIds) {
            long earliest = earliestEventTime(ledger, decisionId).orElseThrow(() -> new IllegalArgumentException(
                    "decision " + PythonRepr.string(decisionId)
                            + " has no recorded events, so it cannot be split by time"));
            (earliest >= splitAt ? holdout : training).add(decisionId);
        }

        Set<String> heldOut = new HashSet<>(holdout);
        Set<String> cited = new TreeSet<>(SortKey::compareCodePoints);
        for (String orderId : recommendation.supportingOrderIds()) {
            if (heldOut.contains(orderId)) {
                cited.add(orderId);
            }
        }
        if (!cited.isEmpty()) {
            throw new SupportingEvidenceInHoldoutError("recommendation "
                    + PythonRepr.string(recommendation.recommendationId()) + " cites held-out decision(s) "
                    + PythonRepr.strings(new ArrayList<>(cited)) + "; it may not be scored on its own evidence");
        }

        List<DecisionOutcome> outcomes = new ArrayList<>(holdout.size());
        for (String decisionId : holdout) {
            Arm baseline = arm(decisionId, baselinePin, evaluator, validator);
            Arm treatment = arm(decisionId, treatmentPin, evaluator, validator);
            outcomes.add(new DecisionOutcome(decisionId,
                    verdictFor(decisionId, baseline.run(), treatment.run(), baselinePin, treatmentPin, higherIsBetter),
                    baseline.verdict().valid(), treatment.verdict().valid(),
                    baseline.verdict().codes(), treatment.verdict().codes(), realisedRisk(ledger, decisionId)));
        }
        return new HoldoutEvaluation(recommendation.recommendationId(), splitAt, baselinePin, treatmentPin,
                training, holdout, outcomes);
    }

    /** One arm's run, already judged. */
    private record Arm(OrderRunResult run, ValidationVerdict verdict) {
    }

    private static Optional<Long> earliestEventTime(OutcomeLedger ledger, String decisionId) {
        return ledger.eventsForDecision(decisionId).stream().map(OutcomeEvent::recordedAt).min(Long::compare);
    }

    /** The baseline's realised bad outcomes, as recorded. Negative evidence only. */
    private static List<String> realisedRisk(OutcomeLedger ledger, String decisionId) {
        Set<String> kinds = new TreeSet<>(SortKey::compareCodePoints);
        for (OutcomeEvent event : ledger.currentView(decisionId)) {
            if (NOT_COUNTERFACTUAL.contains(event.eventType())) {
                kinds.add(event.eventType().value());
            }
        }
        return List.copyOf(kinds);
    }

    /** Run one arm and validate it. The verdict is reached before any metric is read. */
    private static Arm arm(String decisionId, ScenarioVersionPin pin, HoldoutEvaluator evaluator,
            IndependentValidator validator) {
        OrderEvaluationArtifact artifact = evaluator.evaluate(decisionId, pin);
        ValidationVerdict verdict = validator.validate(artifact.request(), artifact.result());
        if (!verdict.valid()) {
            // The arm failed this decision. Its metrics are discarded rather than down-weighted:
            // there is no cost at which a rejected placement competes.
            return new Arm(OrderRunResult.failed(decisionId,
                    "independent validation rejected the placement: " + String.join(", ", verdict.codes())), verdict);
        }
        return new Arm(artifact.run(), verdict);
    }

    private static Verdict verdictFor(String decisionId, OrderRunResult baselineRun, OrderRunResult treatmentRun,
            ScenarioVersionPin baselinePin, ScenarioVersionPin treatmentPin, Map<String, Boolean> higherIsBetter) {
        if (!baselineRun.succeeded() && !treatmentRun.succeeded()) {
            return Verdict.UNPACKABLE;
        }
        if (!treatmentRun.succeeded()) {
            return Verdict.REGRESSED;
        }
        if (!baselineRun.succeeded()) {
            // The treatment packed a decision the baseline could not, and its packing passed the
            // validator: an improvement in the only sense available here.
            return Verdict.IMPROVED;
        }
        // Both arms are valid, so the comparison is the forward path's per-decision Pareto report:
        // one profile, two engines, never a blended delta.
        ProfileReport report = Simulation.compareScenarios(
                new ScenarioResult(BASELINE, baselinePin, List.of(decisionId), List.of(baselineRun)),
                new ScenarioResult(TREATMENT, treatmentPin, List.of(decisionId), List.of(treatmentRun)),
                higherIsBetter).get(0);
        Optional<String> winner = report.winner();
        if (winner.equals(Optional.of(TREATMENT))) {
            return Verdict.IMPROVED;
        }
        if (winner.equals(Optional.of(BASELINE))) {
            return Verdict.REGRESSED;
        }
        return Verdict.TRADED_OFF;
    }
}
