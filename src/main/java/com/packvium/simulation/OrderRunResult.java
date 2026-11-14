package com.packvium.simulation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One order's outcome under one version pin.
 *
 * <p>{@code metrics} are Pareto axes -- material cost, shipping cost, utilisation, a damage-risk
 * proxy, runtime -- supplied by the caller's evaluator. This package never computes or invents
 * them. Unlike the Pareto report, which accepts infinities as a caller's encoding of
 * "unpriceable", a run refuses every non-finite value: an infinite <em>measurement</em> is a
 * broken evaluator.
 *
 * @param orderId the order this run answered
 * @param succeeded whether the evaluator produced a usable outcome
 * @param metrics the outcome on each axis, in the evaluator's order; read-only once built
 * @param failureReason why a failed run failed
 * @param artifact whatever raw output the evaluator wants retained beside the metrics
 */
public record OrderRunResult(String orderId, boolean succeeded, Map<String, Double> metrics,
        Optional<String> failureReason, Optional<Object> artifact) {
    /** Refuses an unnamed order, a success without metrics, a failure without a reason, and bad metrics. */
    public OrderRunResult {
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("order_id is required");
        }
        if (succeeded && metrics.isEmpty()) {
            throw new IllegalArgumentException("a succeeded run must report at least one metric");
        }
        if (!succeeded && failureReason.filter(reason -> !reason.isEmpty()).isEmpty()) {
            throw new IllegalArgumentException("a failed run must record a failure_reason");
        }
        Map<String, Double> copy = new LinkedHashMap<>(metrics);
        if (copy.keySet().stream().anyMatch(name -> name == null || name.isEmpty())) {
            throw new IllegalArgumentException("metric names must be non-empty");
        }
        if (copy.values().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("metric values must be finite");
        }
        metrics = Collections.unmodifiableMap(copy);
    }

    /** A run that produced {@code metrics}. */
    public static OrderRunResult succeeded(String orderId, Map<String, Double> metrics) {
        return new OrderRunResult(orderId, true, metrics, Optional.empty(), Optional.empty());
    }

    /** A run that produced no usable outcome, and why. */
    public static OrderRunResult failed(String orderId, String failureReason) {
        return new OrderRunResult(orderId, false, Map.of(), Optional.of(failureReason), Optional.empty());
    }
}
