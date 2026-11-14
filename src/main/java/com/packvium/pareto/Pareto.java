package com.packvium.pareto;

import com.packvium.support.PythonRepr;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Pareto report over results, one report per profile.
 *
 * <p>A single blended score hides the trade-off: a number that folds utilisation, runtime and
 * container count together can name a "winner" that is worse on the one axis a caller cares
 * about. Nothing here computes such a number. A result <em>dominates</em> another only if it is
 * no worse on every named axis and strictly better on at least one, and the report per profile
 * is the set nothing dominates, not a ranking.
 *
 * <p>This is pure report generation over caller-supplied metrics. It runs no engine, and it does
 * not validate: a disqualified candidate must be excluded before it reaches this class.
 */
public final class Pareto {
    private Pareto() {
    }

    /**
     * Whether {@code a} dominates {@code b}: no worse on every axis {@code higherIsBetter} names,
     * and strictly better on at least one. An axis absent from {@code higherIsBetter} is not
     * compared. O(k) for k named axes.
     *
     * @param higherIsBetter each compared axis and its direction; there is no default direction
     * @throws InconsistentAxesError if either side lacks an axis {@code higherIsBetter} names
     * @throws NonFiniteMetricError if either side carries a {@code NaN}
     */
    public static boolean dominates(Map<String, Double> a, Map<String, Double> b,
            Map<String, Boolean> higherIsBetter) {
        Set<String> missing = new TreeSet<>(SortKey::compareCodePoints);
        for (String axis : higherIsBetter.keySet()) {
            if (!a.containsKey(axis) || !b.containsKey(axis)) {
                missing.add(axis);
            }
        }
        if (!missing.isEmpty()) {
            throw new InconsistentAxesError(
                    "missing metric axis/axes: " + PythonRepr.strings(new ArrayList<>(missing)));
        }
        // Public and handed bare maps, so it cannot rely on CandidateResult having validated them.
        refuseNan(a, "left candidate");
        refuseNan(b, "right candidate");
        boolean atLeastAsGood = true;
        boolean strictlyBetterSomewhere = false;
        for (Map.Entry<String, Boolean> direction : higherIsBetter.entrySet()) {
            double left = a.get(direction.getKey());
            double right = b.get(direction.getKey());
            boolean higherWins = direction.getValue();
            if (higherWins ? left < right : left > right) {
                atLeastAsGood = false;
            }
            if (higherWins ? left > right : left < right) {
                strictlyBetterSomewhere = true;
            }
        }
        return atLeastAsGood && strictlyBetterSomewhere;
    }

    /**
     * One {@link ProfileReport} per distinct profile among {@code candidates}, in code-point
     * order of the profile name. O(p · c² · k) for p profiles of at most c candidates and k axes:
     * every candidate is tested against every other in its profile.
     *
     * @param higherIsBetter the direction of every compared axis; guessing one wrong (runtime as
     *     "higher is better") would silently invert the whole report, so there is no default
     * @throws IllegalArgumentException if {@code higherIsBetter} is empty
     * @throws ParetoReportError if one profile holds two results for the same engine
     */
    public static List<ProfileReport> generateReport(List<CandidateResult> candidates,
            Map<String, Boolean> higherIsBetter) {
        if (higherIsBetter.isEmpty()) {
            throw new IllegalArgumentException("higher_is_better must name at least one metric axis");
        }
        Map<String, List<CandidateResult>> byProfile = new LinkedHashMap<>();
        for (CandidateResult candidate : candidates) {
            byProfile.computeIfAbsent(candidate.profile(), unused -> new ArrayList<>()).add(candidate);
        }
        List<String> profiles = new ArrayList<>(byProfile.keySet());
        profiles.sort(SortKey::compareCodePoints);

        List<ProfileReport> reports = new ArrayList<>(profiles.size());
        for (String profile : profiles) {
            List<CandidateResult> group = byProfile.get(profile);
            Set<String> engines = new HashSet<>();
            for (CandidateResult candidate : group) {
                engines.add(candidate.engine());
            }
            if (engines.size() != group.size()) {
                throw new ParetoReportError(
                        "profile " + PythonRepr.string(profile) + " has more than one result for the same engine");
            }
            reports.add(frontier(profile, group, higherIsBetter));
        }
        return List.copyOf(reports);
    }

    private static ProfileReport frontier(String profile, List<CandidateResult> group,
            Map<String, Boolean> higherIsBetter) {
        List<String> optimal = new ArrayList<>();
        List<String> dominated = new ArrayList<>();
        for (CandidateResult candidate : group) {
            boolean isDominated = false;
            for (CandidateResult other : group) {
                if (other != candidate && dominates(other.metrics(), candidate.metrics(), higherIsBetter)) {
                    isDominated = true;
                    break;
                }
            }
            (isDominated ? dominated : optimal).add(candidate.engine());
        }
        optimal.sort(SortKey::compareCodePoints);
        dominated.sort(SortKey::compareCodePoints);
        Optional<String> winner = optimal.size() == 1 ? Optional.of(optimal.get(0)) : Optional.empty();
        return new ProfileReport(profile, optimal, dominated, winner);
    }

    /**
     * Refuses a {@code NaN} in one candidate's metrics. O(axes).
     *
     * @param where names the candidate, because an evaluator producing {@code NaN} is a bug
     *     upstream and a message that does not point at it only moves the search
     */
    static void refuseNan(Map<String, Double> metrics, String where) {
        for (Map.Entry<String, Double> metric : metrics.entrySet()) {
            if (metric.getValue() != null && Double.isNaN(metric.getValue())) {
                throw new NonFiniteMetricError(
                        where + ": metric " + PythonRepr.string(metric.getKey()) + " is NaN and cannot be compared");
            }
        }
    }
}
