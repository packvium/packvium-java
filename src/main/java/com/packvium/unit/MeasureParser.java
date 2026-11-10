package com.packvium.unit;

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
            return scaled(text(object.get("value")), unit == null ? defaultUnit : text(unit), rounding);
        }
        if (value instanceof String written) {
            Matcher match = withUnit.matcher(Text.trim(written));
            if (match.matches()) {
                return scaled(Text.trim(match.group(1)), match.group(2), rounding);
            }
            return scaled(written, defaultUnit, rounding);
        }
        return RationalParser.scaled(integer(value), multiplier.applyAsLong(defaultUnit), rounding);
    }

    long scaled(String value, String unit, Rounding rounding) {
        return RationalParser.scaled(value, multiplier.applyAsLong(unit), rounding);
    }

    private long integer(Object value) {
        if (value instanceof Long whole) {
            return whole;
        }
        if (value instanceof Integer whole) {
            return whole;
        }
        if (value instanceof BigInteger) {
            throw new IllegalArgumentException("Scaled unit value exceeds integer range");
        }
        if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(
                    "float " + quantity + " input is intentionally rejected; use a decimal string");
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
