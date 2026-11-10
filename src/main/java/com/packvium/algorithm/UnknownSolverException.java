package com.packvium.algorithm;

/** {@code PackingConfig.solvers} named a solver this library does not implement. */
public final class UnknownSolverException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnknownSolverException(String message) {
        super(message);
    }
}
