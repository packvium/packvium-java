package com.packvium.serialization;

import com.packvium.support.PythonRepr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The built-in conversions the Python engine's request reader applies to a raw configuration value:
 * {@code int(value)} and the iteration {@code tuple(value)} performs, with Python's own refusal
 * text, so a request the rules let through is read, or refused, as the Python engine reads it.
 */
final class PythonCoercion {
    private static final String OUT_OF_RANGE = "integer exceeds the 64-bit range this engine carries";

    private PythonCoercion() {
    }

    /**
     * {@code int(value)}: an integer as it is, a boolean as 0 or 1, a float truncated toward zero,
     * a string parsed after stripping whitespace. O(length of the value).
     */
    static long integer(Object value) {
        if (value instanceof Long whole) {
            return whole;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof BigInteger big) {
            return ranged(big);
        }
        if (value instanceof BigDecimal || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number)) {
                throw new IllegalArgumentException("cannot convert float NaN to integer");
            }
            if (Double.isInfinite(number)) {
                throw new IllegalArgumentException("cannot convert float infinity to integer");
            }
            return ranged(new BigDecimal(number).toBigInteger());
        }
        if (value instanceof String text) {
            return ranged(parse(text));
        }
        throw new IllegalArgumentException(
                "int() argument must be a string, a bytes-like object or a real number, not '" + typeName(value) + "'");
    }

    /** What {@code for x in value} yields: list items, object keys, or a string's characters. */
    static List<Object> iterated(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> object) {
            return new ArrayList<>(object.keySet());
        }
        if (value instanceof String text) {
            List<Object> characters = new ArrayList<>();
            text.codePoints().forEach(point -> characters.add(new String(Character.toChars(point))));
            return characters;
        }
        throw new IllegalArgumentException("'" + typeName(value) + "' object is not iterable");
    }

    static String typeName(Object value) {
        if (value == null) {
            return "NoneType";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Long || value instanceof BigInteger) {
            return "int";
        }
        if (value instanceof BigDecimal || value instanceof Double) {
            return "float";
        }
        if (value instanceof String) {
            return "str";
        }
        return value instanceof List ? "list" : "dict";
    }

    /** {@code int(str)}: surrounding whitespace, one sign, ASCII digits with single underscores between. */
    private static BigInteger parse(String text) {
        String body = strip(text);
        boolean negative = body.startsWith("-");
        if (negative || body.startsWith("+")) {
            body = body.substring(1);
        }
        if (!underscoredDigits(body)) {
            throw new IllegalArgumentException("invalid literal for int() with base 10: " + PythonRepr.string(text));
        }
        BigInteger magnitude = new BigInteger(body.replace("_", ""));
        return negative ? magnitude.negate() : magnitude;
    }

    private static boolean underscoredDigits(String digits) {
        if (digits.isEmpty() || digits.charAt(0) == '_' || digits.charAt(digits.length() - 1) == '_') {
            return false;
        }
        for (int index = 0; index < digits.length(); index++) {
            char character = digits.charAt(index);
            boolean valid = character == '_' ? digits.charAt(index - 1) != '_' : character >= '0' && character <= '9';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** {@code str.strip()}: Python's whitespace, which includes the no-break space and U+0085. */
    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.charAt(start))) {
            start++;
        }
        while (end > start && space(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean space(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character) || character == '\u0085';
    }

    private static long ranged(BigInteger value) {
        if (value.bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException(OUT_OF_RANGE);
        }
        return value.longValue();
    }
}
