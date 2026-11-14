package com.packvium.outcomes;

/**
 * A payload field is not in the closed vocabulary of its event type. This is the ledger's PII
 * guard: a field it does not explicitly know -- a free-text note that might hold a name or an
 * address -- is refused at construction, never silently stored or silently dropped.
 */
public final class UnsupportedOutcomeFieldError extends OutcomeError {
    private static final long serialVersionUID = 1L;

    /** @param message names the refused and the allowed fields */
    public UnsupportedOutcomeFieldError(String message) {
        super(message);
    }
}
