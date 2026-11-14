package com.packvium.recommendations;

/** Base class for every recommendation-domain error. */
public class RecommendationError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message the reason */
    public RecommendationError(String message) {
        super(message);
    }
}
