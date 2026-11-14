package com.packvium.holdout;

/**
 * Checks one packing independently of the evaluator that produced it. Separate on purpose: an
 * engine that graded its own homework would make the gate a comment. It is handed the packing
 * and never a score.
 */
@FunctionalInterface
public interface IndependentValidator {
    /**
     * @param request the request the arm packed
     * @param result the packing it produced
     * @return whether the packing is valid, and which rules it failed if not
     */
    ValidationVerdict validate(Object request, Object result);
}
