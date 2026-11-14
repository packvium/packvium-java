package com.packvium.recommendations;

import java.util.List;

/**
 * One reviewable proposal. It mutates nothing by existing: a recommendation holds no registry,
 * and the only route from one to a published change is an explicit approval call.
 *
 * @param recommendationId the caller's id for the proposal
 * @param proposal what is proposed, in the caller's words
 * @param supportingOrderIds the paired orders the deltas were computed over
 * @param expectedDeltas one delta per metric both arms reported, sorted by metric name
 * @param confidence the paired share of the whole order corpus, from 0 to 1
 * @param constraints what bounds the proposal, each one non-empty
 * @param rollbackPlan how to undo it if approved
 */
public record Recommendation(
        String recommendationId,
        String proposal,
        List<String> supportingOrderIds,
        List<ExpectedDelta> expectedDeltas,
        double confidence,
        List<String> constraints,
        String rollbackPlan) {
    /** Refuses a proposal that is missing any of the evidence it must carry. */
    public Recommendation {
        if (recommendationId == null || recommendationId.isEmpty()) {
            throw new IllegalArgumentException("recommendation_id is required");
        }
        if (proposal == null || proposal.isEmpty()) {
            throw new IllegalArgumentException("proposal is required");
        }
        supportingOrderIds = List.copyOf(supportingOrderIds);
        expectedDeltas = List.copyOf(expectedDeltas);
        constraints = List.copyOf(constraints);
        if (supportingOrderIds.isEmpty()) {
            throw new IllegalArgumentException("a recommendation must cite at least one supporting order");
        }
        if (expectedDeltas.isEmpty()) {
            throw new IllegalArgumentException("a recommendation must report at least one expected delta");
        }
        if (!(0.0 <= confidence && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (constraints.isEmpty() || constraints.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("a recommendation must state at least one non-empty constraint");
        }
        if (rollbackPlan == null || rollbackPlan.isEmpty()) {
            throw new IllegalArgumentException("a recommendation must state its rollback plan");
        }
    }
}
