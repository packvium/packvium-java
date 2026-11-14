package com.packvium.holdout;

import com.packvium.simulation.OrderRunResult;
import java.util.Objects;

/**
 * One arm's run of one decision, plus the pair an independent check needs.
 *
 * <p>A run summary retains scores, rates and ids but not placement geometry, so it cannot be
 * independently validated. This envelope requires the request and the result for that reason:
 * the validator is given the packing, not a summary of it.
 *
 * @param run the arm's metrics for the decision
 * @param request the request the arm packed, as the validator reads it
 * @param result the packing the arm produced, as the validator reads it
 */
public record OrderEvaluationArtifact(OrderRunResult run, Object request, Object result) {
    /** Refuses an artifact without its request/result pair. */
    public OrderEvaluationArtifact {
        Objects.requireNonNull(run, "run");
        if (request == null || result == null) {
            throw new IllegalArgumentException(
                    "a holdout artifact must carry the request/result pair the validator reads; "
                            + "a run summary alone cannot be independently validated");
        }
    }
}
