package com.packvium.support;

import java.math.BigInteger;

/** Exact integer products and quotients that would overflow a long if multiplied first. */
public final class Arithmetic {
    private Arithmetic() {
    }

    /**
     * Exact floor(a * b / d) for non-negative inputs. Contact areas are squared tick counts and
     * weights are eighth-microgram counts, so their product routinely exceeds a long.
     */
    public static long mulDiv(long a, long b, long d) {
        requirePositive(d);
        if (a == 0 || b == 0) {
            return 0;
        }
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (high == 0 && low >= 0) {
            return low / d;
        }
        return product(a, b).divide(BigInteger.valueOf(d)).longValueExact();
    }

    /**
     * Exact ceil(a * b / d) for non-negative inputs. Every inexact division in the rating model
     * rounds up, so a quote never comes out a minor unit below what the tariff charges.
     */
    public static long mulDivCeil(long a, long b, long d) {
        requirePositive(d);
        if (a == 0 || b == 0) {
            return 0;
        }
        BigInteger[] quotientAndRemainder = product(a, b).divideAndRemainder(BigInteger.valueOf(d));
        long quotient = quotientAndRemainder[0].longValueExact();
        return quotientAndRemainder[1].signum() == 0 ? quotient : Math.addExact(quotient, 1);
    }

    private static BigInteger product(long a, long b) {
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
    }

    private static void requirePositive(long divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("Divisor must be positive");
        }
    }
}
