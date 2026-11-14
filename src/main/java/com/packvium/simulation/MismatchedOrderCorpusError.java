package com.packvium.simulation;

/**
 * Baseline and treatment did not run the identical order corpus. Comparing them would not
 * isolate the effect of the version pin, so the comparison is refused rather than intersected.
 */
public final class MismatchedOrderCorpusError extends ScenarioError {
    private static final long serialVersionUID = 1L;

    /** @param message the refusal */
    public MismatchedOrderCorpusError(String message) {
        super(message);
    }
}
