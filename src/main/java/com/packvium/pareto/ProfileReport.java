package com.packvium.pareto;

import java.util.List;
import java.util.Optional;

/**
 * One profile's Pareto frontier.
 *
 * <p>{@code winner} is set only when exactly one candidate survived as non-dominated. A genuine
 * trade-off leaves it empty and lists every surviving engine in {@code paretoOptimal} instead,
 * rather than breaking the tie with a blended score.
 *
 * @param profile the profile this report covers
 * @param paretoOptimal the engines nothing else dominates, sorted by code point
 * @param dominated the engines some other candidate dominates, sorted by code point
 * @param winner the single Pareto-optimal engine, when there is exactly one
 */
public record ProfileReport(String profile, List<String> paretoOptimal, List<String> dominated,
        Optional<String> winner) {
    /** Refuses a winner that is not the frontier's only member. */
    public ProfileReport {
        if (profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("profile is required");
        }
        paretoOptimal = List.copyOf(paretoOptimal);
        dominated = List.copyOf(dominated);
        if (winner.isPresent() && !paretoOptimal.contains(winner.get())) {
            throw new IllegalArgumentException("winner, when set, must be one of the Pareto-optimal engines");
        }
        if (winner.isPresent() && paretoOptimal.size() != 1) {
            throw new IllegalArgumentException(
                    "winner can only be set when exactly one candidate is Pareto-optimal");
        }
    }

    /** A report with no named winner. */
    public ProfileReport(String profile, List<String> paretoOptimal, List<String> dominated) {
        this(profile, paretoOptimal, dominated, Optional.empty());
    }
}
