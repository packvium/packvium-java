package com.packvium.pareto;

/**
 * Two candidates being compared do not report the same set of metric axes. An axis missing from
 * one side would silently drop out of the dominance comparison rather than being caught.
 */
public final class InconsistentAxesError extends ParetoReportError {
    private static final long serialVersionUID = 1L;

    /** @param message names the missing axes */
    public InconsistentAxesError(String message) {
        super(message);
    }
}
