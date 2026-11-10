package com.packvium.policy;

/**
 * A rule set this engine cannot honour exactly as written.
 *
 * <p>Structured rather than skipped: a rule quietly dropped for being malformed would let a
 * request pack in a way its own policy forbids, which is the failure the whole contract exists
 * to prevent.
 */
public final class PolicyException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public PolicyException(String message) {
        super(message);
    }
}
