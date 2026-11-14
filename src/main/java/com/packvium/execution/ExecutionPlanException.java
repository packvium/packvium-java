package com.packvium.execution;

/**
 * The adapter was handed something it cannot describe.
 *
 * <p>An exception rather than a status, because unlike an infeasible packing request this is
 * never a well-formed question the model cannot answer -- it is a malformed result, and a plan
 * derived from one would be a document about nothing.
 */
public final class ExecutionPlanException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ExecutionPlanException(String message) {
        super(message);
    }

    public ExecutionPlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
