package com.packvium.holdout;

import com.packvium.simulation.ScenarioVersionPin;

/** Re-packs one historical decision under the registry versions a pin names. */
@FunctionalInterface
public interface HoldoutEvaluator {
    /**
     * @param decisionId the historical decision to replay
     * @param pin the arm's version pin
     * @return the run plus the request/result pair the validator will read
     */
    OrderEvaluationArtifact evaluate(String decisionId, ScenarioVersionPin pin);
}
