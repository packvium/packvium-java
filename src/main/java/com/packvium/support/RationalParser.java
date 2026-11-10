package com.packvium.support;

import com.packvium.unit.Rounding;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RationalParser {
    private static final Pattern MIXED = Pattern.compile("^([+-]?\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)$");
    private static final Pattern SIMPLE = Pattern.compile("^([+-]?\\d+)\\s*/\\s*(\\d+)$");
    private static final Pattern DECIMAL = Pattern.compile("^([+-]?)(\\d+)(?:\\.(\\d+))?$");
    private static final int MAX_FRACTIONAL_DIGITS = 18;
    private static final String OUT_OF_RANGE = "Scaled unit value exceeds integer range";

    private RationalParser() {
    }

    /**
     * Split an integer, decimal, fraction ({@code 3/16}) or mixed fraction ({@code 12 3/8}).
     *
     * <p>The sign is taken from the written text, not from the numeric value of the whole
     * part, so {@code -0 1/2} is minus one half rather than plus one half.
     */
    public static Rational parse(String value) {
        String text = Text.trim(value.replace(' ', ' '));
        boolean negative = text.startsWith("-");
        Matcher mixed = MIXED.matcher(text);
        if (mixed.matches()) {
            long denominator = positiveDenominator(mixed.group(3));
            long magnitude = exact(() -> Math.addExact(
                    Math.multiplyExact(Math.abs(digits(mixed.group(1))), denominator), digits(mixed.group(2))));
            return new Rational(negative ? -magnitude : magnitude, denominator);
        }
        Matcher simple = SIMPLE.matcher(text);
        if (simple.matches()) {
            return new Rational(digits(simple.group(1)), positiveDenominator(simple.group(2)));
        }
        Matcher decimal = DECIMAL.matcher(text);
        if (!decimal.matches()) {
            throw new IllegalArgumentException("Invalid decimal or fraction: " + text);
        }
        return decimal(negative, decimal.group(2), decimal.group(3));
    }

    public static Rational parse(long value) {
        return new Rational(value, 1);
    }

    public static long scaled(String value, long multiplier, Rounding rounding) {
        return scaled(parse(value), multiplier, rounding);
    }

    public static long scaled(long value, long multiplier, Rounding rounding) {
        return scaled(parse(value), multiplier, rounding);
    }

    private static long scaled(Rational value, long multiplier, Rounding rounding) {
        long numerator = value.numerator();
        long denominator = value.denominator();
        // Cancel the denominator against the multiplier before multiplying. A value like
        // "1.123456789012345678" mm would otherwise overflow on n * 16000 even though the
        // exact result is a small integer number of ticks.
        long common = gcd(denominator, Math.abs(multiplier));
        long reducedMultiplier = multiplier / common;
        long scaledNumerator = exact(() -> Math.multiplyExact(numerator, reducedMultiplier));
        return round(scaledNumerator, denominator / common, rounding);
    }

    /**
     * Render {@code ticks / divisor} rounded to {@code places} digits, ties to even.
     *
     * <p>Long division on digits rather than a floating division: the serialized value is
     * part of the wire contract, and a double cannot carry a tick count beyond 2^53.
     */
    public static String decimalString(long ticks, long divisor, int places) {
        String sign = ticks < 0 ? "-" : "";
        long magnitude = Math.absExact(ticks);
        long whole = magnitude / divisor;
        long remainder = magnitude % divisor;
        char[] fraction = new char[places];
        for (int index = 0; index < places; index++) {
            remainder *= 10;
            fraction[index] = (char) ('0' + remainder / divisor);
            remainder %= divisor;
        }
        long lastDigit = places > 0 ? fraction[places - 1] - '0' : whole;
        boolean roundUp = remainder * 2 > divisor || (remainder * 2 == divisor && lastDigit % 2 == 1);
        if (roundUp) {
            whole = increment(whole, fraction);
        }
        String digits = stripTrailingZeros(new String(fraction));
        String text = digits.isEmpty() ? Long.toString(whole) : whole + "." + digits;
        return text.equals("0") ? "0" : sign + text;
    }

    private static Rational decimal(boolean negative, String whole, String fraction) {
        if (fraction == null) {
            long value = digits(whole);
            return new Rational(negative ? -value : value, 1);
        }
        if (fraction.length() > MAX_FRACTIONAL_DIGITS) {
            throw new IllegalArgumentException("Decimal has more fractional digits than a tick can distinguish");
        }
        long denominator = pow10(fraction.length());
        long numerator = exact(() -> Math.addExact(Math.multiplyExact(digits(whole), denominator), digits(fraction)));
        return new Rational(negative ? -numerator : numerator, denominator);
    }

    /** Adds one unit at the last fractional digit, carrying into the whole part as needed. */
    private static long increment(long whole, char[] fraction) {
        for (int index = fraction.length - 1; index >= 0; index--) {
            if (fraction[index] != '9') {
                fraction[index]++;
                return whole;
            }
            fraction[index] = '0';
        }
        return whole + 1;
    }

    private static long round(long numerator, long denominator, Rounding rounding) {
        long sign = numerator < 0 ? -1 : 1;
        long magnitude = Math.absExact(numerator);
        long quotient = magnitude / denominator;
        long remainder = magnitude % denominator;
        if (remainder == 0) {
            return sign * quotient;
        }
        return switch (rounding) {
            case FLOOR -> sign < 0 ? -(quotient + 1) : quotient;
            case CEIL -> sign < 0 ? -quotient : quotient + 1;
            case NEAREST -> sign * nearest(quotient, remainder, denominator);
        };
    }

    private static long nearest(long quotient, long remainder, long denominator) {
        // remainder < denominator, so doubling cannot overflow once compared as a difference.
        long rest = denominator - remainder;
        if (remainder < rest) {
            return quotient;
        }
        if (remainder > rest) {
            return quotient + 1;
        }
        return quotient % 2 == 0 ? quotient : quotient + 1;
    }

    private static String stripTrailingZeros(String digits) {
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static long positiveDenominator(String text) {
        long denominator = digits(text);
        if (denominator <= 0) {
            throw new IllegalArgumentException("Fraction denominator must be positive");
        }
        return denominator;
    }

    private static long digits(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException(OUT_OF_RANGE, overflow);
        }
    }

    private static long pow10(int exponent) {
        long value = 1;
        for (int index = 0; index < exponent; index++) {
            value *= 10;
        }
        return value;
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long next = left % right;
            left = right;
            right = next;
        }
        return Math.max(1, left);
    }

    private static long exact(LongSupplier computation) {
        try {
            return computation.getAsLong();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(OUT_OF_RANGE, overflow);
        }
    }
}
