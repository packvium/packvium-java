package com.packvium.pareto;

/** Base class for every error raised by the Pareto report. */
public class ParetoReportError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message the reason, worded as the Python engine words it */
    public ParetoReportError(String message) {
        super(message);
    }
}
