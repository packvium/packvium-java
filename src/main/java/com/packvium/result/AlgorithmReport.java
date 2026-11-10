package com.packvium.result;

public record AlgorithmReport(
        String profile,
        String solver,
        long durationMs,
        long seed,
        boolean timeLimitReached,
        boolean effortLimitReached,
        long candidatesEvaluated,
        long placementsAttempted,
        SolverMetrics metrics) {
}
