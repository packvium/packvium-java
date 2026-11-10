package com.packvium.commerce.policy;

/**
 * A predicate names a scope or operator this engine version does not recognize. Raised
 * at admission time -- an unsupported predicate is refused outright, never silently
 * admitted and then ignored during evaluation.
 */
public final class UnsupportedPredicateException extends PolicyException {
    private static final long serialVersionUID = 1L;

    public UnsupportedPredicateException(String message) {
        super(message);
    }
}
