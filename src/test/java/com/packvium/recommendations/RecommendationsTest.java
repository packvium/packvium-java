package com.packvium.recommendations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.commerce.policy.PolicyAction;
import com.packvium.commerce.policy.PolicyOperator;
import com.packvium.commerce.policy.PolicyRegistry;
import com.packvium.commerce.policy.PolicyScope;
import com.packvium.commerce.policy.Predicate;
import com.packvium.simulation.MismatchedOrderCorpusError;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioResult;
import com.packvium.simulation.ScenarioVersionPin;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proposing a change, and the single route from a proposal to a published one, ported from the
 * reference's tests/test_recommendations.py.
 *
 * <p>A proposal is built from a <em>paired</em> comparison, so an order only one arm could pack
 * contributes to neither mean. And a recommendation is never itself a mutation: it is handed no
 * registry, and the only path to one is an explicit approval call.
 */
class RecommendationsTest {
    static final ScenarioVersionPin PIN = ScenarioVersionPin.ofCatalogVersion(1);
    static final List<String> CONSTRAINTS = List.of("no placement the validator rejects");
    static final String ROLLBACK = "republish catalog version 1";

    /** An order's measurement; a null cost means the arm could not pack it. */
    record Row(String orderId, Double cost, double utilisation) {
    }

    static Row row(String orderId, double cost, double utilisation) {
        return new Row(orderId, cost, utilisation);
    }

    static Row unpackable(String orderId) {
        return new Row(orderId, null, 0);
    }

    static ScenarioResult scenario(String name, Row... table) {
        List<OrderRunResult> runs = new ArrayList<>();
        for (Row row : table) {
            runs.add(row.cost() == null ? OrderRunResult.failed(row.orderId(), "no carton fits")
                    : OrderRunResult.succeeded(row.orderId(), metrics("cost", row.cost(), "utilisation", row.utilisation())));
        }
        return new ScenarioResult(name, PIN, Arrays.stream(table).map(Row::orderId).toList(), runs);
    }

    static ScenarioResult single(String name, Map<String, Double> metrics) {
        return new ScenarioResult(name, PIN, List.of("a"), List.of(OrderRunResult.succeeded("a", metrics)));
    }

    static Map<String, Double> metrics(Object... pairs) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            metrics.put((String) pairs[index], (Double) pairs[index + 1]);
        }
        return metrics;
    }

    static Optional<Recommendation> propose(ScenarioResult baseline, ScenarioResult treatment, int minimumCohortSize,
            double minimumConfidence) {
        return Recommendations.propose("rec-1", "cheaper cartons", baseline, treatment, CONSTRAINTS, ROLLBACK,
                minimumCohortSize, minimumConfidence);
    }

    static Optional<Recommendation> propose(ScenarioResult baseline, ScenarioResult treatment) {
        return propose(baseline, treatment, 1, 0.0);
    }

    static Map<String, List<Double>> means(Recommendation proposal) {
        return proposal.expectedDeltas().stream().collect(Collectors.toMap(ExpectedDelta::metric,
                delta -> List.of(delta.baselineMean(), delta.treatmentMean())));
    }

    @Nested
    class AProposalIsBuiltFromPairedEvidence {
        @Test
        void theDeltasAreTheMeansOfThePairedOrders() {
            Recommendation proposal = propose(scenario("b", row("a", 10.0, 0.6), row("b", 20.0, 0.8)),
                    scenario("t", row("a", 8.0, 0.7), row("b", 16.0, 0.9))).orElseThrow();
            assertEquals(List.of(15.0, 12.0), means(proposal).get("cost"));
            assertEquals(List.of(0.7, 0.8), means(proposal).get("utilisation"));
        }

        /** Report order cannot depend on map order, or two identical comparisons would print differently. */
        @Test
        void deltasAreSortedByMetricName() {
            Recommendation proposal = propose(single("b", metrics("utilisation", 0.6, "cost", 10.0)),
                    single("t", metrics("utilisation", 0.7, "cost", 8.0))).orElseThrow();
            assertEquals(List.of("cost", "utilisation"), proposal.expectedDeltas().stream().map(ExpectedDelta::metric).toList());
        }

        /** Averaging each arm's survivors separately would let who survived look like performance. */
        @Test
        void anOrderOnlyOneArmPackedIsExcludedFromBothMeans() {
            Recommendation proposal = propose(scenario("b", row("a", 10.0, 0.6), row("b", 100.0, 0.1)),
                    scenario("t", row("a", 8.0, 0.7), unpackable("b"))).orElseThrow();
            assertEquals(List.of("a"), proposal.supportingOrderIds());
            assertEquals(10.0, means(proposal).get("cost").get(0));
        }

        @Test
        void confidenceIsThePairedShareOfTheWholeCorpus() {
            Recommendation proposal = propose(
                    scenario("b", row("a", 10.0, 0.6), row("b", 10.0, 0.6), row("c", 10.0, 0.6), row("d", 10.0, 0.6)),
                    scenario("t", row("a", 8.0, 0.7), row("b", 8.0, 0.7), row("c", 8.0, 0.7), unpackable("d")))
                    .orElseThrow();
            assertEquals(0.75, proposal.confidence());
        }

        @Test
        void onlyMetricsBothArmsReportedBecomeDeltas() {
            Recommendation proposal = propose(single("b", metrics("cost", 10.0, "weight", 5.0)),
                    single("t", metrics("cost", 8.0, "volume", 3.0))).orElseThrow();
            assertEquals(List.of("cost"), proposal.expectedDeltas().stream().map(ExpectedDelta::metric).toList());
        }
    }

    @Nested
    class AProposalIsWithheldRatherThanHedged {
        @Test
        void aCohortBelowTheFloorProducesNothing() {
            assertEquals(Optional.empty(),
                    propose(scenario("b", row("a", 10.0, 0.6)), scenario("t", row("a", 8.0, 0.7)), 2, 0.0));
        }

        @Test
        void aConfidenceBelowTheFloorProducesNothing() {
            assertEquals(Optional.empty(), propose(scenario("b", row("a", 10.0, 0.6), row("b", 10.0, 0.6)),
                    scenario("t", row("a", 8.0, 0.7), unpackable("b")), 1, 0.9));
        }

        /** Full cohort, full confidence, and still no axis both sides measured. */
        @Test
        void armsThatShareNoMetricProduceNothing() {
            assertEquals(Optional.empty(),
                    propose(single("b", metrics("cost", 10.0)), single("t", metrics("utilisation", 0.9))));
        }

        @Test
        void twoDifferentCorporaAreRefused() {
            assertThrows(MismatchedOrderCorpusError.class,
                    () -> propose(scenario("b", row("a", 10.0, 0.6)), scenario("t", row("z", 8.0, 0.7))));
        }
    }

    @Nested
    class PublishingIsASeparateExplicitAct {
        private Recommendation recommendation() {
            return new Recommendation("rec-1", "cheaper cartons", List.of("a"),
                    List.of(new ExpectedDelta("cost", 10.0, 8.0)), 1.0, CONSTRAINTS, ROLLBACK);
        }

        @Test
        void approveCallsTheInjectedPublisherExactlyOnce() {
            List<Integer> calls = new ArrayList<>();
            ApprovalRecord record = Recommendations.approve(recommendation(), 1_000, () -> {
                calls.add(1);
                return 7;
            });
            assertEquals(List.of(1), calls);
            assertEquals(new ApprovalRecord("rec-1", 1_000, 7), record);
        }

        @Test
        void aCatalogApprovalPublishesThroughTheRealRegistry() {
            CatalogRegistry registry = new CatalogRegistry("cartons");
            ApprovalRecord record = Recommendations.approveCatalog(recommendation(), registry, new Snapshot(), 1_000, 2_000);
            assertEquals(1, record.publishedVersion());
            assertEquals("rec-1", record.recommendationId());
            assertEquals("approved recommendation rec-1", registry.version(1).note());
        }

        /** Append-only: publishing again never replaces what was published before. */
        @Test
        void aSecondCatalogApprovalAppendsANewVersion() {
            CatalogRegistry registry = new CatalogRegistry("cartons");
            ApprovalRecord first = Recommendations.approveCatalog(recommendation(), registry, new Snapshot(), 1_000, 2_000);
            ApprovalRecord second = Recommendations.approveCatalog(recommendation(), registry, new Snapshot(), 3_000, 4_000);
            assertEquals(List.of(1L, 2L), List.of(first.publishedVersion(), second.publishedVersion()));
        }

        @Test
        void aPolicyApprovalPublishesAVersionedRule() {
            PolicyRegistry registry = new PolicyRegistry();
            ApprovalRecord record = Recommendations.approvePolicy(recommendation(), registry, "no-hazmat-air",
                    PolicyScope.HAZMAT, PolicyAction.REJECT,
                    List.of(new Predicate(PolicyScope.HAZMAT, "class", PolicyOperator.EQUALS, Optional.of("1.4"))),
                    10, 1_000, 2_000);
            assertEquals(1, record.publishedVersion());
            assertEquals("rec-1", record.recommendationId());
        }

        /** Structural, not a convention: the proposal path takes no registry, so it cannot reach a mutation. */
        @Test
        void aProposalIsHandedNoRegistryOfItsOwn() {
            for (Method method : Recommendations.class.getMethods()) {
                if (!method.getName().equals("propose")) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    assertTrue(!parameter.getType().getSimpleName().toLowerCase(Locale.ROOT).contains("registry"),
                            parameter::toString);
                }
            }
        }
    }

    @Nested
    class TheDeltaItself {
        /** Signed on purpose: whether a negative delta is good depends on the axis. */
        @Test
        void deltaIsTreatmentMinusBaseline() {
            assertEquals(-2.0, new ExpectedDelta("cost", 10.0, 8.0).delta());
            assertEquals(0.2, new ExpectedDelta("utilisation", 0.7, 0.9).delta(), 1e-12);
        }

        /** A proposal with nothing measured is advice without evidence. */
        @Test
        void aRecommendationMustReportAtLeastOneDelta() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> new Recommendation(
                    "rec-1", "p", List.of("a"), List.of(), 1.0, CONSTRAINTS, ROLLBACK));
            assertTrue(refusal.getMessage().contains("at least one expected delta"));
        }
    }
}
