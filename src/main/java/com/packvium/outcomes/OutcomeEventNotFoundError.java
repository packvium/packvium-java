package com.packvium.outcomes;

/** No event is recorded under the given event id. */
public final class OutcomeEventNotFoundError extends OutcomeError {
    private static final long serialVersionUID = 1L;

    /** @param message names the missing event id */
    public OutcomeEventNotFoundError(String message) {
        super(message);
    }
}
