package com.packvium.config;

import java.util.Optional;

/**
 * Counted-work limits, checked alongside (never instead of) the wall clock.
 *
 * <p>A wall clock is not a reproducible measure of work; counting lets a caller who needs
 * bit-identical results across machines and loads buy that, as long as the time limit stays
 * generous enough not to trip first. {@code maxRestarts} bounds the portfolio's multi-start
 * loop; the other three are checked against one solver call's own counters.
 */
public record EffortBudget(
        Optional<Long> maxCandidatesEvaluated,
        Optional<Long> maxPlacementAttempts,
        Optional<Long> maxSearchNodes,
        Optional<Long> maxRestarts) {

    public EffortBudget {
        // A limit of zero or less would stop every search before its first step, which no
        // caller means; the schema's floor for each is 1.
        requireAtLeastOne("max_candidates_evaluated", maxCandidatesEvaluated);
        requireAtLeastOne("max_placement_attempts", maxPlacementAttempts);
        requireAtLeastOne("max_search_nodes", maxSearchNodes);
        requireAtLeastOne("max_restarts", maxRestarts);
    }

    private static void requireAtLeastOne(String name, Optional<Long> limit) {
        if (limit.isPresent() && limit.get() < 1) {
            throw new IllegalArgumentException("effort_budget." + name + " must be at least 1");
        }
    }
}
