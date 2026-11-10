package com.packvium.commerce.rating;

/** Base class for every rating-domain failure. */
public class RatingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RatingException(String message) {
        super(message);
    }
}
