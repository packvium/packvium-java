package com.packvium.simulation;

import com.packvium.support.PythonRepr;
import java.util.List;

/**
 * The complete, immutable record of one scenario run: which version pin, which orders (the
 * corpus itself, not just its length), and every order's raw outcome, retained in full.
 *
 * @param scenarioId the caller's name for the scenario
 * @param versionPin what the scenario ran against
 * @param orderIds the order corpus, in evaluation order
 * @param runs one outcome per order, in the same order
 */
public record ScenarioResult(String scenarioId, ScenarioVersionPin versionPin, List<String> orderIds,
        List<OrderRunResult> runs) {
    /** Refuses an unnamed scenario, an empty corpus, and runs that do not match the corpus one to one. */
    public ScenarioResult {
        orderIds = List.copyOf(orderIds);
        runs = List.copyOf(runs);
        if (scenarioId == null || scenarioId.isEmpty()) {
            throw new IllegalArgumentException("scenario_id is required");
        }
        if (orderIds.isEmpty()) {
            throw new IllegalArgumentException("order_ids cannot be empty");
        }
        if (!runs.stream().map(OrderRunResult::orderId).toList().equals(orderIds)) {
            throw new IllegalArgumentException("runs must correspond 1:1 to order_ids, in the same order");
        }
    }

    /** How many orders produced a usable outcome. */
    public int succeededCount() {
        return (int) runs.stream().filter(OrderRunResult::succeeded).count();
    }

    /** How many orders did not. */
    public int failedCount() {
        return runs.size() - succeededCount();
    }

    /**
     * The fraction of orders that produced a usable outcome. Always computed, so that two callers
     * cannot derive it differently.
     */
    public double confidence() {
        return (double) succeededCount() / runs.size();
    }

    /**
     * The run recorded for one order. O(o).
     *
     * @throws ScenarioError if the scenario has no run for {@code orderId}
     */
    public OrderRunResult rawArtifact(String orderId) {
        for (OrderRunResult run : runs) {
            if (run.orderId().equals(orderId)) {
                return run;
            }
        }
        throw new ScenarioError("no run recorded for order " + PythonRepr.string(orderId));
    }
}
