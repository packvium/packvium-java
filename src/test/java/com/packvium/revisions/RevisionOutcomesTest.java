package com.packvium.revisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.holdout.DecisionOutcome;
import com.packvium.holdout.Holdout;
import com.packvium.holdout.HoldoutEvaluation;
import com.packvium.holdout.OrderEvaluationArtifact;
import com.packvium.holdout.ValidationVerdict;
import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import com.packvium.recommendations.ExpectedDelta;
import com.packvium.recommendations.Recommendation;
import com.packvium.serialization.RequestPacker;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioVersionPin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Approved revisions feed historical evaluation without touching a plan, held to Python's
 * {@code test_revision_outcomes.py} at tag v1.4.0. */
class RevisionOutcomesTest {
    private static final ScenarioVersionPin BASELINE_PIN = ScenarioVersionPin.ofCatalogVersion(1);
    private static final ScenarioVersionPin TREATMENT_PIN = ScenarioVersionPin.ofCatalogVersion(2);

    private static List<Map<String, Object>> revisions() {
        return PlanRevisionTest.chain().get(0);
    }

    @Test
    void eachExceptionBecomesOneLedgerEventNamedByItsRevision() {
        Map<String, Object> revision = revisions().get(1);
        List<OutcomeEvent> events = RevisionOutcomes.events(revision, "order-1", 500);
        String digest = PlanRevision.digest(revision);
        assertEquals(List.of("order-1@" + digest + "#1", "order-1@" + digest + "#2"),
                events.stream().map(OutcomeEvent::eventId).toList());
        assertEquals(List.of(OutcomeEventType.OPERATOR_OVERRIDE, OutcomeEventType.ITEM_MISSING),
                events.stream().map(OutcomeEvent::eventType).toList());
        assertEquals(Map.of("item_type", "cube", "quantity", 1L), events.get(1).payload());
    }

    @Test
    void theRootRevisionRecordsNothing() {
        assertEquals(List.of(), RevisionOutcomes.events(revisions().get(0), "order-1", 500));
    }

    @Test
    void aDocumentThatIsNotARevisionIsRefused() {
        assertThrows(PlanRevisionException.class,
                () -> RevisionOutcomes.events(Map.of("events", List.of()), "order-1", 500));
    }

    @Test
    void recordingARevisionTwiceChangesNothing() {
        OutcomeLedger ledger = new OutcomeLedger();
        List<OutcomeEvent> first = RevisionOutcomes.record(ledger, revisions().get(2), "order-1", 500);
        List<OutcomeEvent> again = RevisionOutcomes.record(ledger, revisions().get(2), "order-1", 500);
        assertEquals(first, again);
        assertEquals(1, ledger.eventsForDecision("order-1").size());
    }

    @Test
    void recordingNeverChangesTheRevisionOrItsPlan() {
        List<Map<String, Object>> revisions = revisions();
        String before = PlanRevision.canonicalJson(revisions);
        RevisionOutcomes.record(new OutcomeLedger(), revisions.get(1), "order-1", 500);
        assertEquals(before, PlanRevision.canonicalJson(revisions));
    }

    @Test
    void aLockedPlacementIsReportedAgainstTheBaselineInHoldout() {
        List<Map<String, Object>> revisions = revisions();
        OutcomeLedger ledger = new OutcomeLedger();
        RevisionOutcomes.record(ledger, revisions.get(1), "trained-1", 100);
        RevisionOutcomes.record(ledger, revisions.get(1), "held-1", 900);
        RevisionOutcomes.record(ledger, revisions.get(2), "held-1", 950);
        Object request = revisions.get(2).get("request");
        Recommendation recommendation = new Recommendation("rec-1", "cheaper cartons", List.of("trained-1"),
                List.of(new ExpectedDelta("cost", 10.0, 8.0)), 1.0, List.of("no rejected placement"), "republish");
        Map<String, Boolean> directions = new LinkedHashMap<>(Map.of("cost", false));
        HoldoutEvaluation evaluation = Holdout.evaluateOnHistory(recommendation, ledger, List.of("trained-1", "held-1"),
                BASELINE_PIN, TREATMENT_PIN,
                (decisionId, pin) -> new OrderEvaluationArtifact(OrderRunResult.succeeded(decisionId,
                        Map.of("cost", pin.equals(BASELINE_PIN) ? 10.0 : 8.0)), request, RequestPacker.pack(request)),
                (packed, result) -> ((List<?>) ((Map<?, ?>) result).get("warnings")).isEmpty()
                        ? ValidationVerdict.accepted()
                        : ValidationVerdict.rejected(List.of("warning")),
                directions, 800);
        assertEquals(List.of("trained-1"), evaluation.trainingDecisionIds());
        assertEquals(1, evaluation.decisions().size());
        DecisionOutcome decision = evaluation.decisions().get(0);
        assertEquals("improved", decision.verdict().value());
        assertEquals(List.of("operator_override"), decision.baselineRealisedRisk());
    }

    @Test
    void theLedgerCannotChangeAPacking() {
        List<Map<String, Object>> revisions = revisions();
        Object request = revisions.get(2).get("request");
        Map<String, Object> before = RequestPacker.pack(request);
        RevisionOutcomes.record(new OutcomeLedger(), revisions.get(1), "order-1", 500);
        Map<String, Object> after = RequestPacker.pack(request);
        assertEquals(before.get("containers"), after.get("containers"));
        assertEquals(before.get("score"), after.get("score"));
    }
}
