package com.packvium.algorithm;

import com.packvium.config.EffortBudget;
import java.util.function.LongSupplier;

/**
 * Monotonic budget that can hand bounded sub-budgets to individual multi-starts.
 *
 * <p>An attached {@link EffortBudget} is read against a live {@link SearchStats}, so
 * {@link #expired()} trips on whichever bound -- time or counted work -- is hit first. This is
 * the only class that reads a clock.
 */
public final class Deadline {
    /** A multi-start never gets less, or a long start list would hand out slices too small to place one item. */
    public static final long MINIMUM_SLICE_NS = 1_000_000;

    private final long limitNs;
    private final LongSupplier clock;
    private final long started;
    private final EffortBudget effortBudget;
    private final SearchStats stats;

    private Deadline(long limitNs, LongSupplier clock, EffortBudget effortBudget, SearchStats stats) {
        this.limitNs = limitNs;
        this.clock = clock;
        this.effortBudget = effortBudget;
        this.stats = stats;
        this.started = clock.getAsLong();
    }

    public static Deadline ofMilliseconds(long milliseconds) {
        return ofMilliseconds(milliseconds, System::nanoTime);
    }

    public static Deadline ofMilliseconds(long milliseconds, LongSupplier clock) {
        return new Deadline(Math.multiplyExact(milliseconds, 1_000_000L), clock, null, null);
    }

    public long elapsedMs() {
        return (clock.getAsLong() - started) / 1_000_000;
    }

    public long remainingNs() {
        return limitNs - (clock.getAsLong() - started);
    }

    public boolean effortExceeded() {
        return effortBudget != null && stats != null && exceeded(effortBudget, stats);
    }

    public boolean expired() {
        return effortExceeded() || remainingNs() <= 0;
    }

    public Deadline slice(long parts) {
        long remaining = remainingNs();
        if (parts <= 1) {
            return new Deadline(remaining, clock, effortBudget, stats);
        }
        return new Deadline(Math.max(remaining / parts, Math.min(remaining, MINIMUM_SLICE_NS)), clock, effortBudget,
                stats);
    }

    /** Bind an effort budget to this start's own stats, keeping the same time bound. */
    public Deadline withEffort(EffortBudget budget, SearchStats startStats) {
        return new Deadline(remainingNs(), clock, budget, startStats);
    }

    public Deadline withoutEffort(SearchStats startStats) {
        return new Deadline(remainingNs(), clock, null, startStats);
    }

    public void check() {
        if (expired()) {
            throw new TimeLimitReached("Packing time limit reached");
        }
    }

    /** The three per-solve counters; {@code maxRestarts} is read by the portfolio loop itself. */
    static boolean exceeded(EffortBudget budget, SearchStats stats) {
        return budget.maxCandidatesEvaluated().map(limit -> stats.candidatesEvaluated >= limit).orElse(false)
                || budget.maxPlacementAttempts().map(limit -> stats.placementsAttempted >= limit).orElse(false)
                || budget.maxSearchNodes().map(limit -> stats.searchNodesExpanded >= limit).orElse(false);
    }
}
