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
     * order of the profile name. For p profiles of at most c candidates and k axes: O(p · c log c)
     * with two axes, by a sweep; O(p · c² · k) otherwise, and for input the sweep cannot read (a
     * missing axis or an absent value), where every candidate is tested against every other.
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
        boolean[] isOptimal = comparable(group, higherIsBetter)
                ? vectorFrontier(vectors(group, higherIsBetter))
                : pairwiseFrontier(group, higherIsBetter);
        List<String> optimal = new ArrayList<>();
        List<String> dominated = new ArrayList<>();
        for (int index = 0; index < group.size(); index++) {
            (isOptimal[index] ? optimal : dominated).add(group.get(index).engine());
        }
        optimal.sort(SortKey::compareCodePoints);
        dominated.sort(SortKey::compareCodePoints);
        Optional<String> winner = optimal.size() == 1 ? Optional.of(optimal.get(0)) : Optional.empty();
        return new ProfileReport(profile, optimal, dominated, winner);
    }

    /**
     * Every candidate tested against every other through {@link #dominates}, O(c² · k). The
     * path for input the vector path cannot read, so its refusals keep their historical order:
     * a missing axis or an absent value is reported exactly as before.
     */
    static boolean[] pairwiseFrontier(List<CandidateResult> group, Map<String, Boolean> higherIsBetter) {
        boolean[] optimal = new boolean[group.size()];
        for (int index = 0; index < group.size(); index++) {
            CandidateResult candidate = group.get(index);
            optimal[index] = true;
            for (CandidateResult other : group) {
                if (other != candidate && dominates(other.metrics(), candidate.metrics(), higherIsBetter)) {
                    optimal[index] = false;
                    break;
                }
            }
        }
        return optimal;
    }

    /**
     * Whether the vector path may answer, as the reference decides it: at least two candidates,
     * each with a value on every compared axis and no absent value anywhere. {@code NaN} cannot
     * reach here; {@link CandidateResult} refuses it.
     */
    private static boolean comparable(List<CandidateResult> group, Map<String, Boolean> higherIsBetter) {
        if (group.size() < 2) {
            return false;
        }
        for (CandidateResult candidate : group) {
            if (!candidate.metrics().keySet().containsAll(higherIsBetter.keySet())
                    || candidate.metrics().containsValue(null)) {
                return false;
            }
        }
        return true;
    }

    /** Each candidate's compared axes, negated where higher is better, so lower is better on every one. */
    private static double[][] vectors(List<CandidateResult> group, Map<String, Boolean> higherIsBetter) {
        double[][] vectors = new double[group.size()][];
        for (int index = 0; index < group.size(); index++) {
            Map<String, Double> metrics = group.get(index).metrics();
            double[] vector = new double[higherIsBetter.size()];
            int axis = 0;
            for (Map.Entry<String, Boolean> direction : higherIsBetter.entrySet()) {
                double value = metrics.get(direction.getKey());
                vector[axis++] = direction.getValue() ? -value : value;
            }
            vectors[index] = vector;
        }
        return vectors;
    }

    /**
     * The same sets {@link #pairwiseFrontier} finds, from minimisation vectors: a candidate is
     * optimal unless a different vector is no greater on every axis. Two axes take the sweep,
     * O(c log c); any other count compares vectors directly, O(c² · k), without the per-pair map
     * lookups and checks of {@link #dominates}.
     */
    static boolean[] vectorFrontier(double[][] vectors) {
        if (vectors.length > 0 && vectors[0].length == 2) {
            return twoAxisFrontier(vectors);
        }
        boolean[] optimal = new boolean[vectors.length];
        for (int index = 0; index < vectors.length; index++) {
            optimal[index] = true;
            for (double[] other : vectors) {
                if (!sameVector(other, vectors[index]) && noGreater(other, vectors[index])) {
                    optimal[index] = false;
                    break;
                }
            }
        }
        return optimal;
    }

    /**
     * Sorted by (x, y), one pass keeps the running least y. A run of equal x survives only at its
     * least y, and only if that y is below every y seen at a smaller x; equal vectors survive
     * together, since neither dominates the other. Comparisons are on primitive values, so
     * {@code -0.0} equals {@code 0.0} as it does in the reference. O(c log c).
     */
    private static boolean[] twoAxisFrontier(double[][] vectors) {
        List<Integer> ordered = new ArrayList<>(vectors.length);
        for (int index = 0; index < vectors.length; index++) {
            ordered.add(index);
        }
        ordered.sort((left, right) -> {
            int byX = compare(vectors[left][0], vectors[right][0]);
            return byX != 0 ? byX : compare(vectors[left][1], vectors[right][1]);
        });
        boolean[] optimal = new boolean[vectors.length];
        boolean seen = false;
        double bestY = 0;
        int position = 0;
        while (position < ordered.size()) {
            double x = vectors[ordered.get(position)][0];
            double y = vectors[ordered.get(position)][1];
            boolean survives = !seen || y < bestY;
            int end = position;
            while (end < ordered.size() && vectors[ordered.get(end)][0] == x) {
                int index = ordered.get(end);
                if (survives && vectors[index][1] == y) {
                    optimal[index] = true;
                }
                end++;
            }
            if (survives) {
                bestY = y;
                seen = true;
            }
            position = end;
        }
        return optimal;
    }

    private static int compare(double left, double right) {
        return left < right ? -1 : left > right ? 1 : 0;
    }

    private static boolean sameVector(double[] left, double[] right) {
        for (int axis = 0; axis < left.length; axis++) {
            if (left[axis] != right[axis]) {
                return false;
            }
        }
        return true;
    }

    private static boolean noGreater(double[] left, double[] right) {
        for (int axis = 0; axis < left.length; axis++) {
            if (left[axis] > right[axis]) {
                return false;
            }
        }
        return true;
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
