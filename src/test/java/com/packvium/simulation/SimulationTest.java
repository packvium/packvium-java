package com.packvium.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.pareto.ProfileReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenario runs and the order-by-order comparison built from them, ported from the reference's
 * tests/test_simulation.py.
 *
 * <p>The comparison's one rule: a scenario is compared <em>per order</em>, never as a blended
 * delta. An aggregate would hide which orders improved and which paid for the improvement.
 */
class SimulationTest {
    static final ScenarioVersionPin PIN = ScenarioVersionPin.ofCatalogVersion(1);
    static final Map<String, Boolean> DIRECTIONS = directions();

    private static Map<String, Boolean> directions() {
        Map<String, Boolean> directions = new LinkedHashMap<>();
        directions.put("cost", false);
        directions.put("utilisation", true);
        return directions;
    }

    /** One arm's measurement of one order; {@code null} cost means the arm could not pack it. */
    record Measured(String orderId, Double cost, double utilisation) {
        static Measured of(String orderId, double cost, double utilisation) {
            return new Measured(orderId, cost, utilisation);
        }

        static Measured unpackable(String orderId) {
            return new Measured(orderId, null, 0);
        }
    }

    /** An evaluator that replays a table. */
    static OrderEvaluator arm(Measured... table) {
        Map<String, Measured> byOrder = new LinkedHashMap<>();
        for (Measured measured : table) {
            byOrder.put(measured.orderId(), measured);
        }
        return (orderId, versionPin) -> {
            Measured measured = byOrder.get(orderId);
            if (measured.cost() == null) {
                return OrderRunResult.failed(orderId, "no carton fits");
            }
            return OrderRunResult.succeeded(orderId, metrics(measured.cost(), measured.utilisation()));
        };
    }

    static Map<String, Double> metrics(double cost, double utilisation) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("cost", cost);
        metrics.put("utilisation", utilisation);
        return metrics;
    }

    @Nested
    class RunningAScenario {
        @Test
        void everyOrderIsEvaluatedInTheGivenSequence() {
            List<String> seen = new ArrayList<>();
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("b", "a", "c"), (orderId, pin) -> {
                seen.add(orderId);
                return OrderRunResult.succeeded(orderId, Map.of("cost", 1.0));
            });
            assertEquals(List.of("b", "a", "c"), seen);
            assertEquals(List.of("b", "a", "c"), result.orderIds());
        }

        /** A scenario replays from stored versions, so the pin is what the evaluator resolves against. */
        @Test
        void thePinReachesTheEvaluatorUnchanged() {
            List<ScenarioVersionPin> seen = new ArrayList<>();
            Simulation.runScenario("s", PIN, List.of("a"), (orderId, pin) -> {
                seen.add(pin);
                return OrderRunResult.succeeded(orderId, Map.of("cost", 1.0));
            });
            assertEquals(List.of(PIN), seen);
        }

        /** Reproducibility is this function's own determinism, not a separate mechanism. */
        @Test
        void theSameArgumentsReproduceAnEqualResult() {
            ScenarioResult first = Simulation.runScenario("s", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)));
            ScenarioResult second = Simulation.runScenario("s", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)));
            assertEquals(first, second);
        }

        @Test
        void aFailedOrderIsCarriedRatherThanDropped() {
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.unpackable("b")));
            assertEquals(List.of(true, false), result.runs().stream().map(OrderRunResult::succeeded).toList());
            assertEquals(Optional.of("no carton fits"), result.runs().get(1).failureReason());
        }

        @Test
        void metricsAreReadOnlyOnceTheRunExists() {
            OrderRunResult run = OrderRunResult.succeeded("a", new LinkedHashMap<>(Map.of("cost", 1.0)));
            assertThrows(UnsupportedOperationException.class, () -> run.metrics().put("cost", 2.0));
        }
    }

    @Nested
    class ARunGuardsItsOwnClaims {
        /** "It worked" with nothing measured is not a comparable result. */
        @Test
        void aSucceededRunMustReportAMetric() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> OrderRunResult.succeeded("a", Map.of()));
            assertTrue(refusal.getMessage().contains("at least one metric"));
        }

        @Test
        void aFailedRunMustSayWhy() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new OrderRunResult("a", false, Map.of(), Optional.empty(), Optional.empty()));
            assertTrue(refusal.getMessage().contains("failure_reason"));
        }

        /**
         * Stricter than the Pareto module, which accepts infinities: a measurement of infinite cost
         * is a broken evaluator, not a caller's encoding of "unpriceable".
         */
        @ParameterizedTest
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void aNonFiniteMetricIsRefusedAtTheRun(double value) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> OrderRunResult.succeeded("a", Map.of("cost", value)));
            assertTrue(refusal.getMessage().contains("finite"));
        }

        /**
         * The reference needs its own guard because {@code True} is an {@code int} in Python. Java's
         * type system refuses a boolean metric before it is built; what remains is a missing value.
         */
        @Test
        void anAbsentValueIsNotAMetric() {
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("cost", null);
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> OrderRunResult.succeeded("a", metrics));
            assertTrue(refusal.getMessage().contains("finite"));
        }
    }

    @Nested
    class ComparingTwoScenarios {
        private List<ProfileReport> compare(List<Measured> baseline, List<Measured> treatment) {
            List<String> orders = baseline.stream().map(Measured::orderId).toList();
            return Simulation.compareScenarios(
                    Simulation.runScenario("base", PIN, orders, arm(baseline.toArray(Measured[]::new))),
                    Simulation.runScenario("treat", PIN, orders, arm(treatment.toArray(Measured[]::new))), DIRECTIONS);
        }

        private Map<String, ProfileReport> byOrder(List<ProfileReport> reports) {
            return reports.stream().collect(Collectors.toMap(ProfileReport::profile, report -> report));
        }

        @Test
        void oneReportPerOrder() {
            List<ProfileReport> reports = compare(List.of(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)),
                    List.of(Measured.of("a", 8.0, 0.9), Measured.of("b", 11.0, 0.85)));
            assertEquals(List.of("a", "b"), reports.stream().map(ProfileReport::profile).toList());
        }

        @Test
        void aDominantArmIsNamedPerOrder() {
            Map<String, ProfileReport> reports = byOrder(compare(
                    List.of(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)),
                    List.of(Measured.of("a", 8.0, 0.9), Measured.of("b", 14.0, 0.6))));
            assertEquals(Optional.of("treatment"), reports.get("a").winner());
            assertEquals(Optional.of("baseline"), reports.get("b").winner());
        }

        /** Order b genuinely trades cost for density, and no single number can report that without deciding it. */
        @Test
        void aRealTradeOffOnOneOrderLeavesThatOrderWithoutAWinner() {
            Map<String, ProfileReport> reports = byOrder(compare(
                    List.of(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)),
                    List.of(Measured.of("a", 8.0, 0.9), Measured.of("b", 14.0, 0.9))));
            assertEquals(Optional.of("treatment"), reports.get("a").winner());
            assertEquals(Optional.empty(), reports.get("b").winner());
            assertEquals(List.of("baseline", "treatment"), reports.get("b").paretoOptimal());
        }

        @Test
        void anOrderOnlyOneArmCouldPackReportsThatArmAlone() {
            Map<String, ProfileReport> reports = byOrder(compare(
                    List.of(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)),
                    List.of(Measured.of("a", 8.0, 0.9), Measured.unpackable("b"))));
            assertEquals(List.of("baseline"), reports.get("b").paretoOptimal());
            // Named winner, and not a win: there was only one candidate to be optimal.
            assertEquals(Optional.of("baseline"), reports.get("b").winner());
        }

        @Test
        void anOrderNeitherArmCouldPackProducesNoReport() {
            List<ProfileReport> reports = compare(List.of(Measured.of("a", 10.0, 0.7), Measured.unpackable("b")),
                    List.of(Measured.of("a", 8.0, 0.9), Measured.unpackable("b")));
            assertEquals(List.of("a"), reports.stream().map(ProfileReport::profile).toList());
        }

        /** Comparing the overlap would let cohort mix manufacture a difference neither arm produced. */
        @Test
        void twoDifferentCorporaAreRefusedRatherThanIntersected() {
            ScenarioResult base = Simulation.runScenario("base", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)));
            ScenarioResult treat = Simulation.runScenario("treat", PIN, List.of("a", "c"),
                    arm(Measured.of("a", 8.0, 0.9), Measured.of("c", 9.0, 0.9)));
            MismatchedOrderCorpusError refusal = assertThrows(MismatchedOrderCorpusError.class,
                    () -> Simulation.compareScenarios(base, treat, DIRECTIONS));
            assertTrue(refusal.getMessage().contains("identical order corpus"));
        }

        @Test
        void theSameOrdersInADifferentSequenceAreRefused() {
            ScenarioResult base = Simulation.runScenario("base", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)));
            ScenarioResult treat = Simulation.runScenario("treat", PIN, List.of("b", "a"),
                    arm(Measured.of("a", 8.0, 0.9), Measured.of("b", 11.0, 0.9)));
            assertThrows(MismatchedOrderCorpusError.class, () -> Simulation.compareScenarios(base, treat, DIRECTIONS));
        }

        /** That is the point of a comparison: one pin per arm, the same orders. */
        @Test
        void theArmsMayBePinnedToDifferentCatalogVersions() {
            ScenarioResult base = Simulation.runScenario("base", PIN, List.of("a"), arm(Measured.of("a", 10.0, 0.7)));
            ScenarioResult treat = new ScenarioResult("treat", ScenarioVersionPin.ofCatalogVersion(2), List.of("a"),
                    List.of(OrderRunResult.succeeded("a", metrics(8.0, 0.9))));
            assertEquals(Optional.of("treatment"), Simulation.compareScenarios(base, treat, DIRECTIONS).get(0).winner());
        }
    }

    /**
     * Confidence and invalid-run counts are properties of the result rather than something each
     * caller re-derives, which keeps two callers from computing them differently.
     */
    @Nested
    class AScenarioReportsItsOwnConfidence {
        private ScenarioResult mixed() {
            return Simulation.runScenario("s", PIN, List.of("a", "b", "c", "d"), arm(Measured.of("a", 10.0, 0.7),
                    Measured.unpackable("b"), Measured.of("c", 12.0, 0.8), Measured.of("d", 9.0, 0.9)));
        }

        @Test
        void theCountsPartitionTheRuns() {
            ScenarioResult result = mixed();
            assertEquals(List.of(3, 1), List.of(result.succeededCount(), result.failedCount()));
            assertEquals(result.runs().size(), result.succeededCount() + result.failedCount());
        }

        @Test
        void confidenceIsTheUsableShare() {
            assertEquals(0.75, mixed().confidence());
        }

        @Test
        void aWhollySuccessfulScenarioHasFullConfidence() {
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("a"), arm(Measured.of("a", 10.0, 0.7)));
            assertEquals(1.0, result.confidence());
            assertEquals(0, result.failedCount());
        }

        @Test
        void aWhollyFailedScenarioHasNoConfidence() {
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("a"), arm(Measured.unpackable("a")));
            assertEquals(0.0, result.confidence());
            assertEquals(0, result.succeededCount());
        }
    }

    @Nested
    class ReachingOneOrdersRawRun {
        @Test
        void theRunForAKnownOrderComesBack() {
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("a", "b"),
                    arm(Measured.of("a", 10.0, 0.7), Measured.of("b", 12.0, 0.8)));
            assertEquals(12.0, result.rawArtifact("b").metrics().get("cost"));
        }

        /** Returning null would push the mistake downstream into a metrics lookup. */
        @Test
        void anUnknownOrderIsRefusedByName() {
            ScenarioResult result = Simulation.runScenario("s", PIN, List.of("a"), arm(Measured.of("a", 10.0, 0.7)));
            ScenarioError refusal = assertThrows(ScenarioError.class, () -> result.rawArtifact("zzz"));
            assertEquals("no run recorded for order 'zzz'", refusal.getMessage());
        }
    }

    /** The pin's own guards, which the reference exercises only through the modules that build pins. */
    @Nested
    class AVersionPinGuardsItsOwnClaims {
        @Test
        void anEmptyPinIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> new ScenarioVersionPin(Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        @Test
        void policyPinsAreStoredSortedAndMayNotRepeatARule() {
            ScenarioVersionPin pin = new ScenarioVersionPin(Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(new PolicyVersionPin("b", 1), new PolicyVersionPin("a", 2)), Optional.empty());
            assertEquals(List.of("a", "b"), pin.policyVersions().stream().map(PolicyVersionPin::ruleId).toList());
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new ScenarioVersionPin(Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of(new PolicyVersionPin("a", 1), new PolicyVersionPin("a", 2)), Optional.empty()));
            assertEquals("a version pin cannot name the same policy rule twice", refusal.getMessage());
        }
    }
}
