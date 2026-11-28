package com.packvium.unit;

import com.packvium.support.ObjectRefusal;
import com.packvium.support.JsonValue;
import com.packvium.support.Rational;
import com.packvium.support.RationalParser;
import com.packvium.support.Text;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a request measure -- an integer, a string with an optional unit, or {value, unit}. */
final class MeasureParser {
    private final Pattern withUnit;
    private final ToLongFunction<String> multiplier;
    private final String quantity;

    MeasureParser(Pattern withUnit, ToLongFunction<String> multiplier, String quantity) {
        this.withUnit = withUnit;
        this.multiplier = multiplier;
        this.quantity = quantity;
    }

    long ticks(Object value, String defaultUnit, Rounding rounding) {
        if (value instanceof Map<?, ?> object) {
            Object unit = object.get("unit");
            String stated = unit == null ? defaultUnit : text(unit);
            Object amount = object.get("value");
            if (amount instanceof Boolean) {
                multiplier.applyAsLong(stated);
                throw notAMeasure();
            }
            if (isFloat(amount)) {
                // The reference reads an object's value through the same rule as a bare number,
                // with the generic word: a fraction in binary floating point is refused.
                long unitMultiplier = multiplier.applyAsLong(stated);
                return signed(RationalParser.parse(integer(amount, "measure")), unitMultiplier, rounding);
            }
            return scaled(text(amount), stated, rounding);
        }
        if (value instanceof String written) {
            Matcher match = withUnit.matcher(Text.trim(written));
            if (match.matches()) {
                return scaled(Text.trim(match.group(1)), match.group(2), rounding);
            }
            return scaled(written, defaultUnit, rounding);
        }
        long unitMultiplier = multiplier.applyAsLong(defaultUnit);
        if (value instanceof Boolean) {
            throw notAMeasure();
        }
        return signed(RationalParser.parse(integer(value, quantity)), unitMultiplier, rounding);
    }

    /** A boolean is a number to some JSON readers, but never a measure anyone wrote. */
    private static ObjectRefusal notAMeasure() {
        return new ObjectRefusal("wrong_type", "a boolean is not a measure");
    }

    long scaled(String value, String unit, Rounding rounding) {
        long unitMultiplier = multiplier.applyAsLong(unit);
        return signed(RationalParser.parse(value), unitMultiplier, rounding);
    }

    /**
     * A negative amount is refused as negative before its tick count is computed, so one past the
     * 64-bit range is still named for its sign, as the Python engine's unbounded integers name it.
     */
    private long signed(Rational amount, long unitMultiplier, Rounding rounding) {
        if (amount.numerator() < 0) {
            throw new ObjectRefusal("negative_measure", quantity + " cannot be negative");
        }
        return RationalParser.scaled(amount, unitMultiplier, rounding);
    }

    private static boolean isFloat(Object value) {
        return value instanceof BigDecimal || value instanceof Double || value instanceof Float;
    }

    private long integer(Object value, String word) {
        if (value instanceof Long whole) {
            return whole;
        }
        if (value instanceof Integer whole) {
            return whole;
        }
        if (value instanceof BigInteger big) {
            if (big.bitLength() < Long.SIZE) {
                return big.longValue();
            }
            if (big.signum() < 0) {
                throw new ObjectRefusal("negative_measure", quantity + " cannot be negative");
            }
            throw new IllegalArgumentException("Scaled unit value exceeds integer range");
        }
        if (isFloat(value)) {
            // A fraction in binary floating point is not the decimal the caller wrote; an integral
            // float is exact, and JavaScript cannot send anything else for `100`.
            BigInteger whole = JsonValue.wholeNumber(value instanceof Float single ? single.doubleValue() : value);
            if (whole == null) {
                throw new ObjectRefusal("wrong_type",
                        "float " + word + " input is intentionally rejected; use a decimal string");
            }
            return integer(whole, word);
        }
        throw new IllegalArgumentException("Invalid " + quantity + ": " + value);
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
