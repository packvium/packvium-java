package com.packvium.validation;

import com.packvium.support.PythonRepr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures to exact ticks, re-derived from the unit tables rather than taken from the engine: a
 * validator built on the engine's unit parser could not catch a bug in it.
 */
final class Units {
    /** Length ticks per unit, in the reference's order. One tick is 1/16000 mm. */
    static final Map<String, BigInteger> LENGTH = table(new Object[] {
        "mm", 16_000L, "cm", 160_000L, "m", 16_000_000L, "in", 406_400L, "inch", 406_400L,
        "inches", 406_400L, "ft", 4_876_800L, "tick", 1L, "ticks", 1L});
    /** Weight ticks per unit. One tick is 1/8 microgram. */
    static final Map<String, BigInteger> WEIGHT = table(new Object[] {
        "mg", 8_000L, "g", 8_000_000L, "kg", 8_000_000_000L, "oz", 226_796_185L, "lb", 3_628_738_960L,
        "lbs", 3_628_738_960L, "tick", 1L, "ticks", 1L});

    static final BigInteger TICKS_PER_GRAM = BigInteger.valueOf(8_000_000L);
    static final BigInteger TICKS_PER_KG = BigInteger.valueOf(8_000_000_000L);

    private static final List<String> SIDES = List.of("length", "width", "height");
    /**
     * {@code Fraction(text)} by CPython 3.11's grammar: optional sign, then {@code n/d}, or a
     * decimal with an optional exponent; digit groups may be split by single underscores. Only
     * ASCII digits are read.
     */
    private static final Pattern FRACTION = Pattern.compile(
            "^([-+]?)(?=[0-9]|\\.[0-9])([0-9]+(?:_[0-9]+)*)?(?:/([0-9]+(?:_[0-9]+)*)"
                    + "|(?:\\.([0-9]+(?:_[0-9]+)*)?)?(?:[eE]([-+]?[0-9]+(?:_[0-9]+)*))?)$");
    /** The largest decimal exponent this port computes with; Python's limit is memory. */
    private static final int MAX_EXPONENT = 400;

    private Units() {
    }

    private static Map<String, BigInteger> table(Object[] pairs) {
        Map<String, BigInteger> table = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            table.put((String) pairs[index], BigInteger.valueOf((Long) pairs[index + 1]));
        }
        return table;
    }

    /** {@code table[unit]}, an exact key. */
    static BigInteger perUnit(Map<String, BigInteger> table, String unit) {
        BigInteger ticks = table.get(unit);
        if (ticks == null) {
            throw MalformedResult.shape("unknown unit " + PythonRepr.string(unit));
        }
        return ticks;
    }

    /**
     * A measure (a number, a string with an optional unit suffix, or {@code {value, unit}}) to
     * exact ticks, rounding halves to even.
     */
    static BigInteger toTicks(Object measure, String defaultUnit, Map<String, BigInteger> table) {
        String unit = defaultUnit;
        Object raw = measure;
        if (measure instanceof Map<?, ?> object) {
            raw = Json.at(object, "value");
            if (object.containsKey("unit")) {
                unit = Json.text(object.get("unit"), "a measure's unit");
            }
        } else if (measure instanceof String text) {
            String stripped = strip(text);
            for (String suffix : bySuffixLength(table)) {
                String lowered = stripped.toLowerCase(Locale.ROOT);
                if (lowered.endsWith(suffix) && !strip(stripped.substring(0, stripped.length() - suffix.length())).isEmpty()) {
                    raw = strip(stripped.substring(0, stripped.length() - suffix.length()));
                    unit = suffix;
                    break;
                }
            }
        }
        Ratio scaled = parseRational(raw).multiply(Ratio.integer(perUnit(table, unit.toLowerCase(Locale.ROOT))));
        return roundHalfEven(scaled);
    }

    /** The table's units, longest first; ties keep the table's order, as Python's stable sort does. */
    private static List<String> bySuffixLength(Map<String, BigInteger> table) {
        List<String> suffixes = new ArrayList<>(table.keySet());
        suffixes.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return suffixes;
    }

    private static BigInteger roundHalfEven(Ratio scaled) {
        BigInteger floor = scaled.floor();
        Ratio remainder = Ratio.of(scaled.numerator().subtract(floor.multiply(scaled.denominator())),
                scaled.denominator());
        int order = remainder.compareTo(Ratio.of(BigInteger.ONE, Exact.TWO));
        if (order > 0) {
            return floor.add(BigInteger.ONE);
        }
        if (order < 0) {
            return floor;
        }
        return floor.testBit(0) ? floor.add(BigInteger.ONE) : floor;
    }

    /**
     * {@code parse_rational}: an integer exactly, anything else through its {@code str()} as a
     * fraction, a mixed fraction ({@code 12 3/8}) when the text holds a space.
     */
    static Ratio parseRational(Object value) {
        BigInteger whole = Json.intInstance(value);
        if (whole != null) {
            return Ratio.integer(whole);
        }
        String stripped = strip(PyFormat.str(value));
        if (stripped.indexOf(' ') < 0) {
            return parseFraction(stripped);
        }
        int split = 0;
        while (split < stripped.length() && !isSpace(stripped.codePointAt(split))) {
            split += Character.charCount(stripped.codePointAt(split));
        }
        String first = stripped.substring(0, split);
        Ratio fraction = parseFraction(stripped.substring(split));
        if (first.startsWith("-")) {
            fraction = fraction.negate();
        }
        return parseFraction(first).add(fraction);
    }

    /** {@code Fraction(str(value))}, the reference's reading of a ratio field. */
    static Ratio ratioOf(Object value) {
        return parseFraction(PyFormat.str(value));
    }

    static Ratio parseFraction(String written) {
        String text = strip(written);
        Matcher parts = FRACTION.matcher(text);
        if (!parts.matches()) {
            throw MalformedResult.shape("Invalid literal for Fraction: " + PyFormat.string(written));
        }
        BigInteger numerator = digits(parts.group(2));
        BigInteger denominator = BigInteger.ONE;
        if (parts.group(3) != null) {
            denominator = digits(parts.group(3));
        } else {
            String decimals = parts.group(4) == null ? "" : parts.group(4).replace("_", "");
            if (!decimals.isEmpty()) {
                BigInteger scale = BigInteger.TEN.pow(decimals.length());
                numerator = numerator.multiply(scale).add(new BigInteger(decimals));
                denominator = scale;
            }
            if (parts.group(5) != null) {
                BigInteger power = new BigInteger(parts.group(5).replace("_", "").replace("+", ""));
                if (power.abs().compareTo(BigInteger.valueOf(MAX_EXPONENT)) > 0) {
                    throw MalformedResult.shape("arithmetic overflow: an exponent beyond what this validator computes");
                }
                BigInteger factor = BigInteger.TEN.pow(power.abs().intValue());
                if (power.signum() < 0) {
                    denominator = denominator.multiply(factor);
                } else {
                    numerator = numerator.multiply(factor);
                }
            }
        }
        if (parts.group(1).equals("-")) {
            numerator = numerator.negate();
        }
        return Ratio.of(numerator, denominator);
    }

    private static BigInteger digits(String group) {
        return group == null ? BigInteger.ZERO : new BigInteger(group.replace("_", ""));
    }

    /** Python's {@code str.strip()}: its whitespace from both ends. */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    /** Python's {@code str.isspace} for one code point, fixed rather than read from this JDK. */
    private static boolean isSpace(int code) {
        return (code >= 0x09 && code <= 0x0d) || (code >= 0x1c && code <= 0x20) || code == 0x85 || code == 0xa0
                || code == 0x1680 || (code >= 0x2000 && code <= 0x200a) || code == 0x2028 || code == 0x2029
                || code == 0x202f || code == 0x205f || code == 0x3000;
    }

    static List<BigInteger> dimensions(Object value, String unit) {
        List<BigInteger> sides = new ArrayList<>(3);
        for (String side : SIDES) {
            sides.add(toTicks(Json.at(value, side), unit, LENGTH));
        }
        return sides;
    }

    /** A JSON float's {@code str()} is its {@code repr}; kept here so a measure reads it alike. */
    static String floatText(BigDecimal value) {
        return PythonRepr.floating(value.doubleValue());
    }
}
