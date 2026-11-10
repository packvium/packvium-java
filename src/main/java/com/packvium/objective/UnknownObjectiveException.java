package com.packvium.objective;

/** {@code PackingConfig.objective} named an objective this library does not implement. */
public final class UnknownObjectiveException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnknownObjectiveException(String message) {
        super(message);
    }
}
