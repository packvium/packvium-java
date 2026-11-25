package com.packvium.commerce;

/**
 * The supplied document or request is not well formed -- a caller bug.
 *
 * <p>Kept apart from a rejection on purpose: a well-formed request
 * the commercial model simply cannot answer is a successful call returning
 * status "rejected", not an exception.
 */
public final class CommerceInputException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public CommerceInputException(String message) {
        super(message);
    }

    public CommerceInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
