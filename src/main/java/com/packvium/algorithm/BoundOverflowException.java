package com.packvium.algorithm;

/**
 * A sum in the bound path exceeded the declared ceiling.
 *
 * <p>Not an invalid request: the bound declines to answer because answering would mean
 * carrying a number this engine cannot hold exactly, and a bound that is quietly wrong is
 * worse than none. Every engine refuses at the same declared ceiling, not its native limit.
 */
public final class BoundOverflowException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BoundOverflowException(String message) {
        super(message);
    }
}
