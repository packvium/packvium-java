package com.packvium.outcomes;

/** Base class for every outcome-domain error. */
public class OutcomeError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message the reason, worded as the reference engine words it */
    public OutcomeError(String message) {
        super(message);
    }
}
