package com.packvium.pareto;

/**
 * A metric is {@code NaN}, which cannot take part in a dominance comparison.
 *
 * <p>Dominance is decided by {@code >} and {@code <}, and both are false for {@code NaN}, so an
 * axis carrying one would set neither "better" nor "worse" and be counted as equal: a candidate
 * whose metrics were all {@code NaN} would reach the frontier beside a clean one, because nothing
 * could dominate it.
 *
 * <p>Infinities are allowed on purpose. They are ends of the number line, not holes in it, so a
 * caller encoding "unpriceable" as an infinite cost gets exactly the dominance answer they mean.
 */
public final class NonFiniteMetricError extends ParetoReportError {
    private static final long serialVersionUID = 1L;

    /** @param message names the candidate and the axis that carried the {@code NaN} */
    public NonFiniteMetricError(String message) {
        super(message);
    }
}
