package com.packvium.holdout;

import com.packvium.simulation.ScenarioVersionPin;
import java.util.List;

/**
 * The complete record of one holdout evaluation. It keeps the split, both pins and every
 * per-decision verdict -- not just the counts -- so the run can be argued with rather than
 * merely believed.
 *
 * @param recommendationId the recommendation that was scored
 * @param splitAt the instant that separated training from holdout
 * @param baselinePin what the baseline arm replayed against
 * @param treatmentPin what the treatment arm replayed against
 * @param trainingDecisionIds decisions first recorded before the split, in the order given
 * @param holdoutDecisionIds decisions first recorded at or after it, in the order given
 * @param decisions one outcome per held-out decision, in the same order
 */
public record HoldoutEvaluation(String recommendationId, long splitAt, ScenarioVersionPin baselinePin,
        ScenarioVersionPin treatmentPin, List<String> trainingDecisionIds, List<String> holdoutDecisionIds,
        List<DecisionOutcome> decisions) {
    /** Copies the lists, so the record is immutable. */
    public HoldoutEvaluation {
        trainingDecisionIds = List.copyOf(trainingDecisionIds);
        holdoutDecisionIds = List.copyOf(holdoutDecisionIds);
        decisions = List.copyOf(decisions);
    }

    /** How many held-out decisions improved. */
    public int improved() {
        return count(Verdict.IMPROVED);
    }

    /** How many regressed. */
    public int regressed() {
        return count(Verdict.REGRESSED);
    }

    /** How many traded one axis for another. */
    public int tradedOff() {
        return count(Verdict.TRADED_OFF);
    }

    /** How many neither arm could pack validly. */
    public int unpackable() {
        return count(Verdict.UNPACKABLE);
    }

    private int count(Verdict verdict) {
        return (int) decisions.stream().filter(decision -> decision.verdict() == verdict).count();
    }
}
