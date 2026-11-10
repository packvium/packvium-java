package com.packvium.commerce.policy;

/** Base class for every policy-domain failure. */
public class PolicyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PolicyException(String message) {
        super(message);
    }
}
