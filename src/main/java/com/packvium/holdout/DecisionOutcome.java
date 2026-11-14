package com.packvium.holdout;

import java.util.List;
import java.util.Objects;

/**
 * What the replay found for one held-out decision, retained rather than summarised: every
 * verdict keeps the validator codes that produced it and the realised-risk events the baseline
 * actually incurred.
 *
 * @param decisionId the held-out decision
 * @param verdict what the replay concluded
 * @param baselineValid whether the validator accepted the baseline's packing
 * @param treatmentValid whether it accepted the treatment's
 * @param baselineCodes the rules the baseline failed
 * @param treatmentCodes the rules the treatment failed
 * @param baselineRealisedRisk the non-counterfactual event types recorded against the decision, sorted
 */
public record DecisionOutcome(String decisionId, Verdict verdict, boolean baselineValid, boolean treatmentValid,
        List<String> baselineCodes, List<String> treatmentCodes, List<String> baselineRealisedRisk) {
    /** Refuses an unnamed decision and an improvement the validator rejected. */
    public DecisionOutcome {
        if (decisionId == null || decisionId.isEmpty()) {
            throw new IllegalArgumentException("decision_id is required");
        }
        Objects.requireNonNull(verdict, "verdict");
        baselineCodes = List.copyOf(baselineCodes);
        treatmentCodes = List.copyOf(treatmentCodes);
        baselineRealisedRisk = List.copyOf(baselineRealisedRisk);
        if (verdict == Verdict.IMPROVED && !treatmentValid) {
            throw new IllegalArgumentException(
                    "a packing the validator rejected can never be recorded as an improvement");
        }
    }
}
