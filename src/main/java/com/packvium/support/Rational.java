package com.packvium.support;

/** An exact ratio as written in a request, before it is scaled to ticks. */
public record Rational(long numerator, long denominator) {
    public Rational {
        if (denominator <= 0) {
            throw new IllegalArgumentException("Fraction denominator must be positive");
        }
    }
}
