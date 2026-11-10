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
}
