package com.packvium.recommendations;

import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.commerce.catalog.Version;
import com.packvium.commerce.policy.PolicyAction;
import com.packvium.commerce.policy.PolicyRegistry;
import com.packvium.commerce.policy.PolicyScope;
import com.packvium.commerce.policy.Predicate;
import com.packvium.commerce.policy.Rule;
import com.packvium.simulation.OrderRunResult;
import com.packvium.simulation.ScenarioResult;
import com.packvium.simulation.Simulation;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Carton-catalog and rule-change recommendations, backed by a baseline-versus-treatment
 * comparison over a real order cohort.
 *
 * <p>A recommendation names which orders support it, what changed and by how much per metric,
 * how confident that evidence is, what bounds it and how to undo it. It is never an automatic
 * change to the production catalog: {@link #propose} is handed no registry and cannot reach one.
 * Only an explicit {@link #approve}, {@link #approveCatalog} or {@link #approvePolicy} call
 * publishes, through the commerce registries' own append-only {@code publish}.
 *
 * <p>Thin evidence is suppressed, not hedged: {@link #propose} returns an empty
 * {@link Optional} rather than a recommendation with a misleadingly low confidence attached.
 */
public final class Recommendations {
    private Recommendations() {
    }

    /**
     * Build a recommendation from a paired comparison, or return empty when the evidence is too
     * thin. There are no default thresholds: what counts as enough evidence is a product decision
     * this library does not make for you.
     *
     * <p>Each delta is computed from the same successful order pairs on both sides. Averaging each
     * arm's survivors separately could manufacture an improvement from cohort mix alone. O(o · k)
     * time and space for o orders and at most k metrics both arms report on an order, plus
     * O(m log m) to sort m metric names.
     *
     * @param constraints what bounds the proposal; at least one, none empty
     * @param minimumCohortSize the fewest paired orders that may support a proposal; positive
     * @param minimumConfidence the smallest paired share of the corpus that may; from 0 to 1
     * @return the proposal, or empty when the paired cohort or its share of the corpus falls below
     *     a threshold, or when no metric was reported by both arms
     * @throws com.packvium.simulation.MismatchedOrderCorpusError if the arms ran different corpora
     */
    public static Optional<Recommendation> propose(String recommendationId, String proposal, ScenarioResult baseline,
            ScenarioResult treatment, List<String> constraints, String rollbackPlan, int minimumCohortSize,
            double minimumConfidence) {
        Simulation.requireSameCorpus(baseline, treatment);
        if (minimumCohortSize <= 0) {
            throw new IllegalArgumentException("minimum_cohort_size must be positive");
        }
        if (!(0.0 <= minimumConfidence && minimumConfidence <= 1.0)) {
            throw new IllegalArgumentException("minimum_confidence must be between 0 and 1");
        }
        // Looked up only, never iterated: the deltas are emitted in sorted metric order.
        Map<String, double[]> sums = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        List<String> supportingOrderIds = new ArrayList<>();
        for (int index = 0; index < baseline.runs().size(); index++) {
            OrderRunResult baselineRun = baseline.runs().get(index);
            OrderRunResult treatmentRun = treatment.runs().get(index);
            if (!baselineRun.succeeded() || !treatmentRun.succeeded()) {
                continue;
            }
            supportingOrderIds.add(baselineRun.orderId());
            for (Map.Entry<String, Double> metric : baselineRun.metrics().entrySet()) {
                Double treated = treatmentRun.metrics().get(metric.getKey());
                if (treated == null) {
                    continue;
                }
                // Summed in order-corpus order, the order the reference's sum() visits them in,
                // so the means are the same doubles.
                double[] sum = sums.computeIfAbsent(metric.getKey(), unused -> new double[2]);
                sum[0] += metric.getValue();
                sum[1] += treated;
                counts.merge(metric.getKey(), 1, Integer::sum);
            }
        }

        // Evidence size is the paired cohort, not the orders attempted: two mostly disjoint
        // survivor sets could otherwise advertise a large cohort while a delta rests on one order.
        int comparableCount = supportingOrderIds.size();
        double confidence = (double) comparableCount / baseline.orderIds().size();
        if (comparableCount < minimumCohortSize || confidence < minimumConfidence) {
            return Optional.empty();
        }

        List<String> metrics = new ArrayList<>(sums.keySet());
        metrics.sort(SortKey::compareCodePoints);
        List<ExpectedDelta> deltas = new ArrayList<>(metrics.size());
        for (String metric : metrics) {
            double[] sum = sums.get(metric);
            int count = counts.get(metric);
            deltas.add(new ExpectedDelta(metric, sum[0] / count, sum[1] / count));
        }
        if (deltas.isEmpty()) {
            // No metric both sides produced: nothing to recommend from.
            return Optional.empty();
        }
        return Optional.of(new Recommendation(recommendationId, proposal, supportingOrderIds, deltas, confidence,
                constraints, rollbackPlan));
    }

    /**
     * The generic route from a recommendation to a registry change: calls {@code publish} exactly
     * once and records the version it returns.
     *
     * @param at when the approval happened, in the caller's epoch milliseconds
     * @param publish performs the change and returns the version number it produced
     */
    public static ApprovalRecord approve(Recommendation recommendation, long at, LongSupplier publish) {
        long publishedVersion = publish.getAsLong();
        return new ApprovalRecord(recommendation.recommendationId(), at, publishedVersion);
    }

    /** Publish an approved catalog proposal through the real append-only catalog registry. */
    public static ApprovalRecord approveCatalog(Recommendation recommendation, CatalogRegistry registry,
            Snapshot snapshot, long approvedAt, long effectiveAt) {
        Version version = registry.publish(snapshot, effectiveAt, approvedAt,
                "approved recommendation " + recommendation.recommendationId());
        return new ApprovalRecord(recommendation.recommendationId(), approvedAt, version.number());
    }

    /** Publish an approved policy proposal as a new version of {@code ruleId}. */
    public static ApprovalRecord approvePolicy(Recommendation recommendation, PolicyRegistry registry, String ruleId,
            PolicyScope scope, PolicyAction action, List<Predicate> predicates, long priority, long approvedAt,
            long effectiveAt) {
        Rule rule = registry.publish(ruleId, scope, action, predicates, priority, effectiveAt,
                "approved recommendation " + recommendation.recommendationId());
        return new ApprovalRecord(recommendation.recommendationId(), approvedAt, rule.version());
    }
}
