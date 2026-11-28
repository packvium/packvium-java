package com.packvium.validation;

import java.math.BigInteger;

/**
 * Python's {@code Fraction}: always reduced, the denominator always positive, so the numerator and
 * the denominator read the same numbers the reference reads.
 */
record Ratio(BigInteger numerator, BigInteger denominator) {
    static final Ratio ZERO = new Ratio(BigInteger.ZERO, BigInteger.ONE);

    static Ratio of(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw MalformedResult.shape("Fraction with a zero denominator");
        }
        BigInteger divisor = numerator.gcd(denominator);
        if (divisor.signum() == 0) {
            divisor = BigInteger.ONE;
        }
        BigInteger top = Exact.floorDiv(numerator, divisor);
        BigInteger bottom = Exact.floorDiv(denominator, divisor);
        return bottom.signum() < 0 ? new Ratio(top.negate(), bottom.negate()) : new Ratio(top, bottom);
    }

    static Ratio integer(BigInteger value) {
        return new Ratio(value, BigInteger.ONE);
    }

    Ratio add(Ratio other) {
        return of(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    Ratio multiply(Ratio other) {
        return of(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    Ratio negate() {
        return new Ratio(numerator.negate(), denominator);
    }

    int compareTo(Ratio other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    int compareTo(BigInteger value) {
        return compareTo(integer(value));
    }

    /** {@code numerator // denominator}. */
    BigInteger floor() {
        return Exact.floorDiv(numerator, denominator);
    }

    /**
     * Python's {@code float(fraction)}: the nearest double, ties to even. The quotient is taken to
     * 53 significant bits exactly and the remainder decides the last one. O(size of the operands).
     */
    double toDouble() {
        if (numerator.signum() == 0) {
            return 0.0;
        }
        BigInteger magnitude = numerator.abs();
        int shift = 53 - (magnitude.bitLength() - denominator.bitLength());
        BigInteger[] division = quotient(magnitude, shift);
        if (division[0].bitLength() > 53) {
            shift--;
            division = quotient(magnitude, shift);
        }
        BigInteger rounded = division[0];
        int order = division[1].shiftLeft(1).compareTo(division[2]);
        if (order > 0 || (order == 0 && rounded.testBit(0))) {
            rounded = rounded.add(BigInteger.ONE);
        }
        double value = Math.scalb(rounded.doubleValue(), -shift);
        return numerator.signum() < 0 ? -value : value;
    }

    /** {@code floor(magnitude * 2^shift / denominator)}, its remainder, and the scaled denominator. */
    private BigInteger[] quotient(BigInteger magnitude, int shift) {
        BigInteger top = shift >= 0 ? magnitude.shiftLeft(shift) : magnitude;
        BigInteger bottom = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
        BigInteger[] division = top.divideAndRemainder(bottom);
        return new BigInteger[] {division[0], division[1], bottom};
    }
}
