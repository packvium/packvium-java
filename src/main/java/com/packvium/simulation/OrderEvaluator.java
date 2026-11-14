package com.packvium.simulation;

/**
 * Evaluates one order under one version pin. Supplied by the caller, which keeps pricing, policy
 * evaluation and packing out of the orchestration layer: a scenario is only as deterministic as
 * the evaluator it is given.
 */
@FunctionalInterface
public interface OrderEvaluator {
    /**
     * @param orderId the order to evaluate
     * @param versionPin the registry versions to resolve catalog, tariff and policy against
     * @return the order's outcome; its {@code orderId} must be {@code orderId}
     */
    OrderRunResult evaluate(String orderId, ScenarioVersionPin versionPin);
}
