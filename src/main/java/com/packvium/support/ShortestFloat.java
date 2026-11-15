package com.packvium.support;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The shortest decimal digits that read back as a given double.
 *
 * <p>Python's {@code repr} and ECMAScript's {@code Number::toString} both start from this digit
 * string and lay it out differently. It is computed through {@link BigDecimal} rather than
 * {@link Double#toString}: before JDK 19 that method does not always give the shortest digits,
 * and from JDK 19 on, when one digit would do, it picks the closest of the one- and two-digit
 * decimals ({@code 4.9E-324} where Python writes {@code 5e-324}).
 *
 * <p>For each length from 1 to 17 digits, the exact value rounded half-even to that many
 * significant digits is the correctly rounded candidate, and reading it back says whether it
 * identifies the double. A rounded candidate can miss while the grid point on the other side of
 * the value hits -- just above a power of two the rounding interval is twice as wide on one
 * side -- so that neighbour is tried before a longer length. Among digit strings of the shortest
 * length that read back, the correctly rounded one is the closest, which is the one Python
 * picks. Seventeen significant digits always identify a double. O(1): at most 34 candidates.
 */
public final class ShortestFloat {
    private static final int MAX_DIGITS = 17;

    private ShortestFloat() {
    }

    /**
     * The digits without trailing zeros, and the decimal point position {@code point} for which
     * the value is {@code 0.DIGITS x 10^point}.
     */
    public record Digits(String digits, int point) {
    }

    public static Digits digits(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("shortest digits are defined for a finite positive double");
        }
        BigDecimal exact = new BigDecimal(value);
        for (int length = 1; length <= MAX_DIGITS; length++) {
            BigDecimal rounded = exact.round(new MathContext(length, RoundingMode.HALF_EVEN));
            if (rounded.doubleValue() == value) {
                return normalized(rounded);
            }
            BigDecimal neighbour = otherNeighbour(rounded, exact, length);
            if (neighbour.doubleValue() == value) {
                return normalized(neighbour);
            }
        }
        throw new IllegalStateException("seventeen significant digits always identify a double");
    }

    /**
     * The grid point bracketing the value from the side the rounded candidate is not on. Below a
     * power of ten the grid is ten times finer, so the lower neighbour of {@code 10^(length-1)}
     * units is {@code 10^length - 1} units one decade down.
     */
    private static BigDecimal otherNeighbour(BigDecimal rounded, BigDecimal exact, int length) {
        int unitExponent = rounded.precision() - rounded.scale() - length;
        BigDecimal unit = BigDecimal.ONE.scaleByPowerOfTen(unitExponent);
        if (rounded.compareTo(exact) < 0) {
            return rounded.add(unit);
        }
        BigDecimal mantissa = rounded.scaleByPowerOfTen(-unitExponent);
        if (mantissa.compareTo(BigDecimal.TEN.pow(length - 1)) == 0) {
            return rounded.subtract(unit.movePointLeft(1));
        }
        return rounded.subtract(unit);
    }

    private static Digits normalized(BigDecimal decimal) {
        BigDecimal stripped = decimal.stripTrailingZeros();
        String digits = stripped.unscaledValue().toString();
        return new Digits(digits, digits.length() - stripped.scale());
    }
}
