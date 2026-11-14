package com.packvium.outcomes;

/**
 * The same event id was recorded again with different content. A duplicate delivery must repeat
 * the identical event, never carry a different one under the same id.
 */
public final class DuplicateEventMismatchError extends OutcomeError {
    private static final long serialVersionUID = 1L;

    /** @param message names the event id */
    public DuplicateEventMismatchError(String message) {
        super(message);
    }
}
