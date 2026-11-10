package com.packvium.algorithm;

/** Thrown by {@link Deadline#check()} to unwind a search that ran out of time or effort. */
public final class TimeLimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TimeLimitReached(String message) {
        super(message);
    }
}
