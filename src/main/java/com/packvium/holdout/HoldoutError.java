package com.packvium.holdout;

/** Base class for every error the holdout replay raises. */
public class HoldoutError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message the reason, worded as the reference engine words it */
    public HoldoutError(String message) {
        super(message);
    }
}
