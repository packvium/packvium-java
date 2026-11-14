package com.packvium.pareto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dominance and the report built from it, ported from the reference's tests/test_pareto.py.
 *
 * <p>The module's one job: never collapse a trade-off into a single number. A result dominates
 * another only when it is no worse on every named axis and strictly better on at least one, and
 * when two results survive that, the report says so instead of picking.
 */
class ParetoTest {
    /** Cost is cheaper-is-better, utilisation higher-is-better; stating both is mandatory. */
    static final Map<String, Boolean> DIRECTIONS = directions("cost", false, "utilisation", true);

    static Map<String, Boolean> directions(Object... pairs) {
        Map<String, Boolean> directions = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            directions.put((String) pairs[index], (Boolean) pairs[index + 1]);
        }
        return directions;
    }

    static Map<String, Double> metrics(Object... pairs) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            metrics.put((String) pairs[index], (Double) pairs[index + 1]);
        }
        return metrics;
    }

    static CandidateResult candidate(String engine, double cost, double utilisation, String profile) {
        return new CandidateResult(profile, engine, metrics("cost", cost, "utilisation", utilisation));
    }

    static CandidateResult candidate(String engine, double cost, double utilisation) {
        return candidate(engine, cost, utilisation, "balanced");
    }

    static void assertMessage(String pattern, Throwable thrown) {
        assertTrue(Pattern.compile(pattern).matcher(thrown.getMessage()).find(), thrown::getMessage);
    }

    @Nested
    class Dominance {
        @Test
        void betterOnBothAxesDominates() {
            assertTrue(Pareto.dominates(metrics("cost", 8.0, "utilisation", 0.9),
                    metrics("cost", 10.0, "utilisation", 0.7), DIRECTIONS));
        }

        @Test
        void worseOnBothAxesDoesNot() {
            assertFalse(Pareto.dominates(metrics("cost", 12.0, "utilisation", 0.6),
                    metrics("cost", 10.0, "utilisation", 0.7), DIRECTIONS));
        }

        /**
         * Cheaper but sparser: the case the module exists for. Ranking these would be a claim about
         * the caller's priorities that nothing in the metrics supports.
         */
        @Test
        void aGenuineTradeOffDominatesInNeitherDirection() {
            Map<String, Double> cheap = metrics("cost", 8.0, "utilisation", 0.6);
            Map<String, Double> dense = metrics("cost", 12.0, "utilisation", 0.9);
            assertFalse(Pareto.dominates(cheap, dense, DIRECTIONS));
            assertFalse(Pareto.dominates(dense, cheap, DIRECTIONS));
        }

        /** "No worse on every axis" holds; "strictly better somewhere" does not. */
        @Test
        void identicalMetricsDominateInNeitherDirection() {
            Map<String, Double> same = metrics("cost", 10.0, "utilisation", 0.8);
            assertFalse(Pareto.dominates(same, new LinkedHashMap<>(same), DIRECTIONS));
        }

        @Test
        void equalOnOneAxisAndBetterOnTheOtherDominates() {
            assertTrue(Pareto.dominates(metrics("cost", 10.0, "utilisation", 0.9),
                    metrics("cost", 10.0, "utilisation", 0.8), DIRECTIONS));
        }

        /** The same two candidates with the meaning of cost inverted: the map has no default. */
        @Test
        void directionIsReadFromTheMapRatherThanGuessed() {
            Map<String, Double> low = metrics("cost", 8.0);
            Map<String, Double> high = metrics("cost", 12.0);
            assertTrue(Pareto.dominates(low, high, directions("cost", false)));
            assertTrue(Pareto.dominates(high, low, directions("cost", true)));
        }

        @Test
        void anAxisMissingFromEitherSideIsRefused() {
            InconsistentAxesError left = assertThrows(InconsistentAxesError.class, () -> Pareto.dominates(
                    metrics("cost", 8.0), metrics("cost", 10.0, "utilisation", 0.7), DIRECTIONS));
            assertEquals("missing metric axis/axes: ['utilisation']", left.getMessage());
            InconsistentAxesError right = assertThrows(InconsistentAxesError.class, () -> Pareto.dominates(
                    metrics("cost", 8.0, "utilisation", 0.9), metrics("cost", 10.0), DIRECTIONS));
            assertMessage("utilisation", right);
        }

        /** Extra metrics are data the caller did not ask to rank on, not an error. */
        @Test
        void anAxisAbsentFromTheDirectionMapIsSimplyNotCompared() {
            assertTrue(Pareto.dominates(metrics("cost", 8.0, "weight", 99.0), metrics("cost", 10.0, "weight", 1.0),
                    directions("cost", false)));
        }

        /** {@code NaN > x} and {@code NaN < x} are both false: unguarded, the axis reads as a tie. */
        @Test
        void aNanOnEitherSideIsRefusedRatherThanCountedEqual() {
            NonFiniteMetricError left = assertThrows(NonFiniteMetricError.class, () -> Pareto.dominates(
                    metrics("cost", Double.NaN), metrics("cost", 1.0), directions("cost", false)));
            assertEquals("left candidate: metric 'cost' is NaN and cannot be compared", left.getMessage());
            NonFiniteMetricError right = assertThrows(NonFiniteMetricError.class, () -> Pareto.dominates(
                    metrics("cost", 1.0), metrics("cost", Double.NaN), directions("cost", false)));
            assertMessage("right candidate.*cost", right);
        }

        /** Allowed on purpose: "unpriceable" as an infinite cost gets the answer the caller means. */
        @Test
        void infinitiesCompareAsTheEndsOfTheNumberLine() {
            Map<String, Boolean> cost = directions("cost", false);
            assertTrue(Pareto.dominates(metrics("cost", 5.0), metrics("cost", Double.POSITIVE_INFINITY), cost));
            assertFalse(Pareto.dominates(metrics("cost", Double.POSITIVE_INFINITY), metrics("cost", 5.0), cost));
            assertFalse(Pareto.dominates(metrics("cost", Double.POSITIVE_INFINITY),
                    metrics("cost", Double.POSITIVE_INFINITY), cost));
        }
    }

    @Nested
    class TheReport {
        @Test
        void oneDominantCandidateBecomesTheNamedWinner() {
            List<ProfileReport> reports = Pareto.generateReport(
                    List.of(candidate("python", 10.0, 0.7), candidate("php", 8.0, 0.9)), DIRECTIONS);
            assertEquals(1, reports.size());
            assertEquals(Optional.of("php"), reports.get(0).winner());
            assertEquals(List.of("php"), reports.get(0).paretoOptimal());
            assertEquals(List.of("python"), reports.get(0).dominated());
        }

        @Test
        void aTradeOffLeavesNoWinnerAndListsBoth() {
            List<ProfileReport> reports = Pareto.generateReport(
                    List.of(candidate("python", 8.0, 0.6), candidate("php", 12.0, 0.9)), DIRECTIONS);
            assertEquals(Optional.empty(), reports.get(0).winner());
            assertEquals(List.of("php", "python"), reports.get(0).paretoOptimal());
            assertEquals(List.of(), reports.get(0).dominated());
        }

        /** Nothing can dominate it, so it is on the frontier -- easy to misread as a win. */
        @Test
        void aSingleCandidateIsOptimalByDefault() {
            List<ProfileReport> reports = Pareto.generateReport(List.of(candidate("python", 10.0, 0.7)), DIRECTIONS);
            assertEquals(List.of("python"), reports.get(0).paretoOptimal());
            assertEquals(Optional.of("python"), reports.get(0).winner());
        }

        @Test
        void threeCandidatesSplitIntoFrontierAndDominated() {
            List<ProfileReport> reports = Pareto.generateReport(List.of(candidate("cheap", 8.0, 0.6),
                    candidate("dense", 12.0, 0.9), candidate("worse", 14.0, 0.5)), DIRECTIONS);
            assertEquals(List.of("cheap", "dense"), reports.get(0).paretoOptimal());
            assertEquals(List.of("worse"), reports.get(0).dominated());
            assertEquals(Optional.empty(), reports.get(0).winner());
        }

        @Test
        void eachProfileGetsItsOwnReportSortedByName() {
            List<ProfileReport> reports = Pareto.generateReport(List.of(candidate("python", 10.0, 0.7, "quality"),
                    candidate("php", 8.0, 0.9, "fast")), DIRECTIONS);
            assertEquals(List.of("fast", "quality"), reports.stream().map(ProfileReport::profile).toList());
        }

        /** Report order cannot depend on input order, or two runs would print differently. */
        @Test
        void frontierAndDominatedAreEachSorted() {
            List<ProfileReport> forward = Pareto.generateReport(
                    List.of(candidate("zeta", 8.0, 0.6), candidate("alpha", 12.0, 0.9)), DIRECTIONS);
            List<ProfileReport> reverse = Pareto.generateReport(
                    List.of(candidate("alpha", 12.0, 0.9), candidate("zeta", 8.0, 0.6)), DIRECTIONS);
            assertEquals(List.of("alpha", "zeta"), forward.get(0).paretoOptimal());
            assertEquals(forward.get(0).paretoOptimal(), reverse.get(0).paretoOptimal());
        }

        @Test
        void noCandidatesProducesNoReports() {
            assertEquals(List.of(), Pareto.generateReport(List.of(), DIRECTIONS));
        }

        @Test
        void twoResultsForOneEngineInOneProfileAreRefused() {
            ParetoReportError refusal = assertThrows(ParetoReportError.class, () -> Pareto.generateReport(
                    List.of(candidate("python", 10.0, 0.7), candidate("python", 8.0, 0.9)), DIRECTIONS));
            assertEquals("profile 'balanced' has more than one result for the same engine", refusal.getMessage());
        }

        /** With no axes there is no dominance, so every candidate would be "optimal". */
        @Test
        void anEmptyDirectionMapIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> Pareto.generateReport(List.of(candidate("python", 10.0, 0.7)), Map.of()));
            assertMessage("at least one metric axis", refusal);
        }

        /** Before the refusal existed, an all-NaN candidate came back optimal beside a clean one. */
        @Test
        void aNanCandidateCannotReachTheFrontier() {
            NonFiniteMetricError refusal = assertThrows(NonFiniteMetricError.class, () -> Pareto.generateReport(
                    List.of(candidate("python", 10.0, 0.7), candidate("broken", Double.NaN, Double.NaN)), DIRECTIONS));
            assertEquals("broken on profile balanced: metric 'cost' is NaN and cannot be compared", refusal.getMessage());
        }
    }

    @Nested
    class TheReportTypeGuardsItsOwnClaims {
        @Test
        void aWinnerMustBeOnTheFrontier() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new ProfileReport("fast", List.of("php"), List.of("python"), Optional.of("python")));
            assertMessage("must be one of the Pareto-optimal", refusal);
        }

        @Test
        void aWinnerCannotBeNamedWhenTwoSurvive() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new ProfileReport("fast", List.of("php", "python"), List.of(), Optional.of("php")));
            assertMessage("exactly one candidate", refusal);
        }

        @Test
        void noWinnerAlongsideAFrontierOfTwoIsWellFormed() {
            assertEquals(Optional.empty(), new ProfileReport("fast", List.of("php", "python"), List.of()).winner());
        }
    }
}
