package com.packvium.pareto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The vector frontier, the two-axis sweep included, holds the same sets as the pairwise path it
 * replaces, as the reference's 1.3.0 change does; the pairwise path stays for input the vector
 * path cannot read.
 */
class ParetoFrontierTest {
    /** Few distinct values, so ties, duplicates and signed zeros are common rather than rare. */
    private static final double[] VALUES = {-0.0, 0.0, 1.0, 2.0, 2.5, 3.0, -1.0, 1e300,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

    @Test
    void theVectorFrontierIsThePairwiseFrontier() {
        SplittableRandom random = new SplittableRandom(20261114L);
        int compared = 0;
        for (int axes = 1; axes <= 4; axes++) {
            for (int round = 0; round < 400; round++) {
                Map<String, Boolean> directions = new LinkedHashMap<>();
                for (int axis = 0; axis < axes; axis++) {
                    directions.put("axis" + axis, random.nextBoolean());
                }
                List<CandidateResult> group = new ArrayList<>();
                int size = 2 + random.nextInt(12);
                for (int engine = 0; engine < size; engine++) {
                    Map<String, Double> metrics = new LinkedHashMap<>();
                    for (String axis : directions.keySet()) {
                        metrics.put(axis, VALUES[random.nextInt(VALUES.length)]);
                    }
                    group.add(new CandidateResult("p", "engine" + engine, metrics));
                }
                assertArrayEquals(Pareto.pairwiseFrontier(group, directions),
                        Pareto.vectorFrontier(vectors(group, directions)), "axes " + axes + ", round " + round);
                compared++;
            }
        }
        assertEquals(1600, compared);
    }

    @Test
    void equalVectorsSurviveTogetherAndSignedZerosAreEqual() {
        Map<String, Boolean> directions = ParetoTest.directions("cost", false, "utilisation", true);
        List<CandidateResult> group = List.of(
                candidate("a", 1.0, 0.0), candidate("b", 1.0, -0.0), candidate("c", 2.0, 0.5),
                candidate("d", 1.0, -1.0));
        List<ProfileReport> reports = Pareto.generateReport(group, directions);
        assertEquals(List.of("a", "b", "c"), reports.get(0).paretoOptimal());
        assertEquals(List.of("d"), reports.get(0).dominated());
    }

    @Test
    void inputTheSweepCannotReadStillTakesThePairwisePathAndItsRefusals() {
        Map<String, Boolean> directions = ParetoTest.directions("cost", false, "utilisation", true);
        List<CandidateResult> missingAxis = List.of(candidate("a", 1.0, 0.5),
                new CandidateResult("p", "b", Map.of("cost", 2.0)));
        assertThrows(InconsistentAxesError.class, () -> Pareto.generateReport(missingAxis, directions));
        Map<String, Double> absent = new HashMap<>();
        absent.put("cost", 1.0);
        absent.put("utilisation", null);
        List<CandidateResult> absentValue = List.of(candidate("a", 1.0, 0.5), new CandidateResult("p", "b", absent));
        // Whatever the pairwise path does with an absent value, the report still does.
        Class<? extends Throwable> pairwise = assertThrows(RuntimeException.class,
                () -> Pareto.pairwiseFrontier(absentValue, directions)).getClass();
        assertEquals(pairwise, assertThrows(RuntimeException.class,
                () -> Pareto.generateReport(absentValue, directions)).getClass());
    }

    private static CandidateResult candidate(String engine, double cost, double utilisation) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("cost", cost);
        metrics.put("utilisation", utilisation);
        return new CandidateResult("p", engine, metrics);
    }

    private static double[][] vectors(List<CandidateResult> group, Map<String, Boolean> directions) {
        double[][] vectors = new double[group.size()][];
        for (int index = 0; index < group.size(); index++) {
            double[] vector = new double[directions.size()];
            int axis = 0;
            for (Map.Entry<String, Boolean> direction : directions.entrySet()) {
                double value = group.get(index).metrics().get(direction.getKey());
                vector[axis++] = direction.getValue() ? -value : value;
            }
            vectors[index] = vector;
        }
        return vectors;
    }
}
