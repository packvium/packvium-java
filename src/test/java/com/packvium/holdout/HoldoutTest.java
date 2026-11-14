package com.packvium.holdout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import com.packvium.recommendations.ExpectedDelta;
import com.packvium.recommendations.Recommendation;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioVersionPin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Replaying a recommendation against history it has not seen, ported from the reference's
 * tests/test_holdout.py.
 *
 * <p>Two rules are why this module exists rather than a spreadsheet. A packing the injected
 * validator rejects is that arm's failure <em>at any cost</em>: its metrics never reach the
 * comparison. And a recommendation may not be scored on the evidence that produced it, which is
 * refused rather than warned about.
 */
class HoldoutTest {
    static final ScenarioVersionPin BASELINE_PIN = ScenarioVersionPin.ofCatalogVersion(1);
    static final ScenarioVersionPin TREATMENT_PIN = ScenarioVersionPin.ofCatalogVersion(2);
    static final Map<String, Boolean> DIRECTIONS = new LinkedHashMap<>(Map.of("cost", false, "utilisation", true));

    static Recommendation recommendation(String... supporting) {
        return new Recommendation("rec-1", "cheaper cartons", List.of(supporting),
                List.of(new ExpectedDelta("cost", 10.0, 8.0)), 1.0, List.of("no rejected placement"),
                "republish version 1");
    }

    /** One recorded event: a shipment unless a kind is given. */
    record Recorded(String decisionId, long at, OutcomeEventType kind) {
        Recorded(String decisionId, long at) {
            this(decisionId, at, OutcomeEventType.ACTUAL_CARTON);
        }
    }

    static OutcomeLedger ledgerWith(Recorded... events) {
        OutcomeLedger ledger = new OutcomeLedger();
        for (int index = 0; index < events.length; index++) {
            Recorded event = events[index];
            Map<String, Object> payload = event.kind() == OutcomeEventType.ACTUAL_CARTON
                    ? Map.of("carton_id", "box-a", "catalog_version", 1L)
                    : Map.of("reason_code", "crushed_corner", "severity", "minor");
            ledger.record(new OutcomeEvent("e" + (index + 1), event.decisionId(), event.kind(), payload, event.at()));
        }
        return ledger;
    }

    /** Both arms' {@code (cost, utilisation)} for one decision. */
    record Arms(double baselineCost, double baselineUtilisation, double treatmentCost, double treatmentUtilisation) {
    }

    static final Arms GOOD = new Arms(10.0, 0.7, 8.0, 0.8);

    /** The request/result pair a replay hands its validator: which decision, which arm. */
    record Replayed(String decisionId, String arm) {
    }

    static HoldoutEvaluator evaluator(Map<String, Arms> table) {
        return (decisionId, pin) -> {
            boolean baseline = pin.equals(BASELINE_PIN);
            Arms arms = table.get(decisionId);
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("cost", baseline ? arms.baselineCost() : arms.treatmentCost());
            metrics.put("utilisation", baseline ? arms.baselineUtilisation() : arms.treatmentUtilisation());
            return new OrderEvaluationArtifact(OrderRunResult.succeeded(decisionId, metrics),
                    Map.of("decision_id", decisionId), Map.of("arm", baseline ? "baseline" : "treatment"));
        };
    }

    static IndependentValidator validator(Set<Replayed> invalid) {
        return (request, result) -> invalid.contains(new Replayed((String) ((Map<?, ?>) request).get("decision_id"),
                (String) ((Map<?, ?>) result).get("arm")))
                        ? ValidationVerdict.rejected(List.of("unsupported_item"))
                        : ValidationVerdict.accepted();
    }

    static HoldoutEvaluation evaluate(Map<String, Arms> table, OutcomeLedger ledger, List<String> decisionIds,
            long splitAt, Recommendation recommendation, Set<Replayed> invalid) {
        return Holdout.evaluateOnHistory(recommendation, ledger, decisionIds, BASELINE_PIN, TREATMENT_PIN,
                evaluator(table), validator(invalid), DIRECTIONS, splitAt);
    }

    static HoldoutEvaluation evaluate(Map<String, Arms> table, OutcomeLedger ledger, List<String> decisionIds) {
        return evaluate(table, ledger, decisionIds, 500, recommendation("trained-1"), Set.of());
    }

    @Nested
    class TheSplitIsByRecordedTime {
        private HoldoutEvaluation twoSided() {
            return evaluate(Map.of("trained-1", GOOD, "held-1", GOOD),
                    ledgerWith(new Recorded("trained-1", 100), new Recorded("held-1", 900)), List.of("trained-1", "held-1"));
        }

        @Test
        void decisionsRecordedBeforeTheSplitAreTraining() {
            assertEquals(List.of("trained-1"), twoSided().trainingDecisionIds());
            assertEquals(List.of("held-1"), twoSided().holdoutDecisionIds());
        }

        @Test
        void onlyHeldOutDecisionsAreScored() {
            assertEquals(List.of("held-1"), twoSided().decisions().stream().map(DecisionOutcome::decisionId).toList());
        }

        /** A later correction must not drag a decision across the split it was recorded on the other side of. */
        @Test
        void theSplitUsesTheEarliestEventForADecision() {
            OutcomeLedger ledger = ledgerWith(new Recorded("held-1", 100),
                    new Recorded("held-1", 900, OutcomeEventType.DAMAGE));
            HoldoutEvaluation result = evaluate(Map.of("held-1", GOOD), ledger, List.of("held-1"), 500,
                    recommendation("other"), Set.of());
            assertEquals(List.of("held-1"), result.trainingDecisionIds());
            assertEquals(List.of(), result.decisions());
        }

        /** It has no timestamp, so it cannot be placed on either side; defaulting it would be guessing. */
        @Test
        void aDecisionWithNoRecordedEventsIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> evaluate(Map.of(), new OutcomeLedger(), List.of("never-shipped")));
            assertEquals("decision 'never-shipped' has no recorded events, so it cannot be split by time",
                    refusal.getMessage());
        }

        @Test
        void aNegativeSplitIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> evaluate(Map.of(),
                    ledgerWith(new Recorded("held-1", 900)), List.of("held-1"), -1, recommendation("trained-1"), Set.of()));
            assertEquals("split_at cannot be negative", refusal.getMessage());
        }

        /** An empty holdout would report zero regressions and read as a clean bill. */
        @Test
        void anEmptyDecisionListIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> evaluate(Map.of(), new OutcomeLedger(), List.of()));
            assertEquals("at least one decision id is required", refusal.getMessage());
        }
    }

    @Nested
    class AProposalCannotBeScoredOnItsOwnEvidence {
        @Test
        void aCitedDecisionInTheHoldoutIsRefusedByName() {
            SupportingEvidenceInHoldoutError refusal = assertThrows(SupportingEvidenceInHoldoutError.class,
                    () -> evaluate(Map.of("trained-1", GOOD), ledgerWith(new Recorded("trained-1", 900)),
                            List.of("trained-1")));
            assertEquals("recommendation 'rec-1' cites held-out decision(s) ['trained-1']; it may not be scored on"
                    + " its own evidence", refusal.getMessage());
        }

        @Test
        void theSameDecisionOnTheTrainingSideIsFine() {
            HoldoutEvaluation result = evaluate(Map.of("trained-1", GOOD), ledgerWith(new Recorded("trained-1", 100)),
                    List.of("trained-1"));
            assertEquals(List.of("trained-1"), result.trainingDecisionIds());
        }

        @Test
        void theRefusalIsAHoldoutError() {
            assertTrue(HoldoutError.class.isAssignableFrom(SupportingEvidenceInHoldoutError.class));
        }
    }

    @Nested
    class TheVerdictPerDecision {
        private DecisionOutcome verdict(Arms arms, Set<Replayed> invalid) {
            return evaluate(Map.of("held-1", arms), ledgerWith(new Recorded("held-1", 900)), List.of("held-1"), 500,
                    recommendation("trained-1"), invalid).decisions().get(0);
        }

        private DecisionOutcome verdict(Arms arms) {
            return verdict(arms, Set.of());
        }

        @Test
        void betterOnEveryAxisIsAnImprovement() {
            assertEquals(Verdict.IMPROVED, verdict(new Arms(10.0, 0.7, 8.0, 0.9)).verdict());
        }

        @Test
        void worseOnEveryAxisIsARegression() {
            assertEquals(Verdict.REGRESSED, verdict(new Arms(8.0, 0.9, 10.0, 0.7)).verdict());
        }

        @Test
        void cheaperButSparserIsATradeOffRatherThanAWin() {
            assertEquals(Verdict.TRADED_OFF, verdict(new Arms(10.0, 0.9, 8.0, 0.7)).verdict());
        }

        /** Neither dominates, so neither is named: the report saying "no difference". */
        @Test
        void identicalMetricsAreATradeOffRatherThanAWin() {
            assertEquals(Verdict.TRADED_OFF, verdict(new Arms(10.0, 0.8, 10.0, 0.8)).verdict());
        }

        /**
         * The rule the module exists to enforce: the treatment is cheaper and denser, and the
         * validator refused the placement, so its metrics are discarded rather than discounted.
         */
        @Test
        void aRejectedTreatmentIsARegressionHoweverCheapItWas() {
            DecisionOutcome outcome = verdict(new Arms(16.0, 0.6, 10.0, 0.9), Set.of(new Replayed("held-1", "treatment")));
            assertEquals(Verdict.REGRESSED, outcome.verdict());
            assertFalse(outcome.treatmentValid());
            assertEquals(List.of("unsupported_item"), outcome.treatmentCodes());
        }

        @Test
        void aRejectedBaselineLeavesAValidTreatmentAnImprovement() {
            DecisionOutcome outcome = verdict(new Arms(8.0, 0.9, 10.0, 0.7), Set.of(new Replayed("held-1", "baseline")));
            assertEquals(Verdict.IMPROVED, outcome.verdict());
            assertFalse(outcome.baselineValid());
        }

        @Test
        void bothArmsRejectedIsUnpackableRatherThanATie() {
            DecisionOutcome outcome = verdict(new Arms(10.0, 0.7, 8.0, 0.9),
                    Set.of(new Replayed("held-1", "baseline"), new Replayed("held-1", "treatment")));
            assertEquals(Verdict.UNPACKABLE, outcome.verdict());
        }
    }

    @Nested
    class RealisedRiskIsReportedAgainstTheArmThatShipped {
        /** Damage happened under the carton that shipped; crediting the treatment would score a counterfactual. */
        @Test
        void recordedDamageIsAttachedToTheBaseline() {
            OutcomeLedger ledger = ledgerWith(new Recorded("held-1", 900),
                    new Recorded("held-1", 950, OutcomeEventType.DAMAGE));
            HoldoutEvaluation result = evaluate(Map.of("held-1", new Arms(10.0, 0.7, 8.0, 0.9)), ledger,
                    List.of("held-1"));
            assertEquals(List.of("damage"), result.decisions().get(0).baselineRealisedRisk());
        }

        @Test
        void aCleanHistoryCarriesNoRealisedRisk() {
            HoldoutEvaluation result = evaluate(Map.of("held-1", new Arms(10.0, 0.7, 8.0, 0.9)),
                    ledgerWith(new Recorded("held-1", 900)), List.of("held-1"));
            assertEquals(List.of(), result.decisions().get(0).baselineRealisedRisk());
        }
    }

    @Nested
    class TheTally {
        @Test
        void eachVerdictIsCountedOnce() {
            Map<String, Arms> table = Map.of("up", new Arms(10.0, 0.7, 8.0, 0.9), "down", new Arms(8.0, 0.9, 10.0, 0.7),
                    "sideways", new Arms(10.0, 0.9, 8.0, 0.7));
            HoldoutEvaluation result = evaluate(table,
                    ledgerWith(new Recorded("up", 900), new Recorded("down", 910), new Recorded("sideways", 920)),
                    List.of("up", "down", "sideways"));
            assertEquals(List.of(1, 1, 1, 0),
                    List.of(result.improved(), result.regressed(), result.tradedOff(), result.unpackable()));
        }

        @Test
        void theEvaluationRecordsBothPinsItCompared() {
            HoldoutEvaluation result = evaluate(Map.of("held-1", new Arms(10.0, 0.7, 8.0, 0.9)),
                    ledgerWith(new Recorded("held-1", 900)), List.of("held-1"));
            assertEquals(BASELINE_PIN, result.baselinePin());
            assertEquals(TREATMENT_PIN, result.treatmentPin());
            assertEquals(500, result.splitAt());
        }
    }

    @Nested
    class TheArtifactCarriesWhatTheValidatorReads {
        private OrderRunResult run() {
            return OrderRunResult.succeeded("d1", Map.of("cost", 1.0));
        }

        @Test
        void aCompleteArtifactIsAccepted() {
            assertEquals("d1", new OrderEvaluationArtifact(run(), Map.of("a", 1L), Map.of("b", 2L)).run().orderId());
        }

        /** The validator is handed the request/result pair precisely so that no score can influence it. */
        @Test
        void anArtifactWithoutTheRequestIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new OrderEvaluationArtifact(run(), null, Map.of("b", 2L)));
            assertTrue(refusal.getMessage().contains("request/result pair"));
        }

        @Test
        void anArtifactWithoutTheResultIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new OrderEvaluationArtifact(run(), Map.of("a", 1L), null));
            assertTrue(refusal.getMessage().contains("request/result pair"));
        }
    }
}
