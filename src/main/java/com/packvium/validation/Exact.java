package com.packvium.validation;

import java.math.BigInteger;
import java.util.List;

/**
 * Integer arithmetic with Python's semantics: exact at any size, with floor division and a modulo
 * that takes the divisor's sign. A zero divisor is Python's {@code ZeroDivisionError}.
 */
final class Exact {
    static final BigInteger TWO = BigInteger.TWO;

    private Exact() {
    }

    static BigInteger of(long value) {
        return BigInteger.valueOf(value);
    }

    /** Python's {@code a // b}. */
    static BigInteger floorDiv(BigInteger a, BigInteger b) {
        if (b.signum() == 0) {
            throw MalformedResult.shape("division by zero");
        }
        BigInteger[] quotient = a.divideAndRemainder(b);
        if (quotient[1].signum() != 0 && (a.signum() < 0) != (b.signum() < 0)) {
            return quotient[0].subtract(BigInteger.ONE);
        }
        return quotient[0];
    }

    /** Python's {@code a % b}. */
    static BigInteger mod(BigInteger a, BigInteger b) {
        return a.subtract(floorDiv(a, b).multiply(b));
    }

    /** {@code -(-a // b)}. */
    static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        return floorDiv(a.negate(), b).negate();
    }

    static BigInteger sum(List<BigInteger> values) {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger value : values) {
            total = total.add(value);
        }
        return total;
    }
}
