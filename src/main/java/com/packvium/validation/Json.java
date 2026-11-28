package com.packvium.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The decoded JSON documents read the way the reference validator's Python reads them.
 *
 * <p>Values are what {@link com.packvium.serialization.JsonReader} returns: a {@link Map}, a
 * {@link List}, a {@link String}, a {@link Long} or {@link BigInteger}, a {@link BigDecimal} for
 * any number with a fraction or an exponent (read as the double Python's {@code json} module reads
 * it), a {@link Boolean}, or {@code null}. Every integer the validator computes with is a
 * {@link BigInteger}, as exact as Python's {@code int}.
 */
final class Json {
    private static final Pattern PY_INT = Pattern.compile("^\\s*([+-]?)(\\d+(?:_\\d+)*)\\s*$");

    private Json() {
    }

    static boolean isDict(Object value) {
        return value instanceof Map;
    }

    static boolean isList(Object value) {
        return value instanceof List;
    }

    static Map<?, ?> dict(Object value) {
        return (Map<?, ?>) value;
    }

    /** Python's {@code key in value} for an object; anything else does not hold it. */
    static boolean has(Object value, String key) {
        return value instanceof Map<?, ?> object && object.containsKey(key);
    }

    /**
     * Python's {@code d.get(key, default)}: the default only when the key is absent, so a key
     * present as {@code null} still reads as {@code null}. A non-object has no {@code .get}.
     */
    static Object get(Object value, String key, Object fallback) {
        if (!(value instanceof Map<?, ?> object)) {
            throw MalformedResult.shape("expected an object holding \"" + key + "\", got " + kind(value));
        }
        return object.containsKey(key) ? object.get(key) : fallback;
    }

    static Object get(Object value, String key) {
        return get(value, key, null);
    }

    /** Python's {@code d[key]}. */
    static Object at(Object value, String key) {
        if (!(value instanceof Map<?, ?> object) || !object.containsKey(key)) {
            throw MalformedResult.shape("missing \"" + key + "\"");
        }
        return object.get(key);
    }

    /** Something the reference iterates; only a list is admitted. */
    static List<?> items(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw MalformedResult.shape(what + " must be a list, got " + kind(value));
        }
        return list;
    }

    static String text(Object value, String what) {
        if (!(value instanceof String text)) {
            throw MalformedResult.shape(what + " must be a string, got " + kind(value));
        }
        return text;
    }

    /**
     * An integer the reference does arithmetic on. A boolean is Python's {@code int}; a float
     * would carry the reference into float arithmetic this port does not follow, so it is refused.
     */
    static BigInteger integer(Object value, String what) {
        BigInteger whole = intInstance(value);
        if (whole == null) {
            throw MalformedResult.shape(what + " must be an integer, got " + kind(value));
        }
        return whole;
    }

    static BigInteger optionalInteger(Object value, String what) {
        return value == null ? null : integer(value, what);
    }

    /** {@code isinstance(value, int)}, which a boolean satisfies. */
    static BigInteger intInstance(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? BigInteger.ONE : BigInteger.ZERO;
        }
        return strictInt(value);
    }

    /** {@code isinstance(value, int) and not isinstance(value, bool)}. */
    static BigInteger strictInt(Object value) {
        if (value instanceof Long whole) {
            return BigInteger.valueOf(whole);
        }
        return value instanceof BigInteger whole ? whole : null;
    }

    /** Python's {@code int(value)}: a number truncates, a string parses as a base-10 integer. */
    static BigInteger pyInt(Object value, String what) {
        BigInteger whole = intInstance(value);
        if (whole != null) {
            return whole;
        }
        if (value instanceof BigDecimal) {
            double number = floating(value);
            if (Double.isFinite(number)) {
                return new BigDecimal(number).toBigInteger();
            }
        }
        if (value instanceof String text) {
            Matcher parts = PY_INT.matcher(text);
            if (parts.matches()) {
                BigInteger magnitude = new BigInteger(parts.group(2).replace("_", ""));
                return parts.group(1).equals("-") ? magnitude.negate() : magnitude;
            }
        }
        throw MalformedResult.shape(what + ": int() cannot convert " + kind(value));
    }

    /** The double Python's {@code json} module reads a number with a fraction or exponent as. */
    static double floating(Object value) {
        return ((BigDecimal) value).doubleValue();
    }

    /** Python truthiness. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof BigDecimal) {
            return floating(value) != 0;
        }
        BigInteger whole = strictInt(value);
        if (whole != null) {
            return whole.signum() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return !((Map<?, ?>) value).isEmpty();
    }

    /**
     * Python {@code ==}: a boolean equals its integer, an integer equals a float of the same value
     * exactly, lists compare element-wise and objects regardless of key order.
     */
    static boolean equals(Object left, Object right) {
        Object leftNumber = number(left);
        Object rightNumber = number(right);
        if (leftNumber != null || rightNumber != null) {
            return leftNumber != null && rightNumber != null && compareNumbers(leftNumber, rightNumber) == 0;
        }
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof String || right instanceof String) {
            return left.equals(right);
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int index = 0; index < leftList.size(); index++) {
                if (!equals(leftList.get(index), rightList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (leftMap.size() != rightMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
                if (!rightMap.containsKey(entry.getKey()) || !equals(entry.getValue(), rightMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** {@code value == number} for an exact integer. */
    static boolean equalsInt(Object value, BigInteger number) {
        Object read = number(value);
        return read != null && compareNumbers(read, number) == 0;
    }

    /** {@code value == text}. */
    static boolean isText(Object value, String text) {
        return value instanceof String written && written.equals(text);
    }

    /**
     * Python's ordering for what the reference sorts or compares: numbers with numbers, strings
     * with strings by code point, lists element-wise. Anything else is Python's {@code TypeError}.
     */
    static int compare(Object left, Object right) {
        Object leftNumber = number(left);
        Object rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return compareNumbers(leftNumber, rightNumber);
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            return Integer.signum(com.packvium.support.SortKey.compareCodePoints(leftText, rightText));
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            for (int index = 0; index < leftList.size(); index++) {
                if (index >= rightList.size()) {
                    return 1;
                }
                if (!equals(leftList.get(index), rightList.get(index))) {
                    return compare(leftList.get(index), rightList.get(index));
                }
            }
            return Integer.compare(leftList.size(), rightList.size());
        }
        throw MalformedResult.shape("cannot order " + kind(left) + " against " + kind(right));
    }

    /** Sorts {@code values} by Python's ordering; stable, as Python's sort is. */
    static <T> List<T> sorted(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        copy.sort(Json::compare);
        return copy;
    }

    /**
     * The identity Python hashes a set member or dict key by, so {@code 1}, {@code 1.0} and
     * {@code True} share one. A list or an object is unhashable.
     */
    static Object hashKey(Object value) {
        Object number = number(value);
        if (number != null) {
            return number;
        }
        if (value == null || value instanceof String) {
            return value == null ? NoneKey.NONE : value;
        }
        throw MalformedResult.shape(kind(value) + " is unhashable");
    }

    static String kind(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (strictInt(value) != null) {
            return "an integer";
        }
        if (value instanceof BigDecimal) {
            return "a number";
        }
        if (value instanceof String) {
            return "a string";
        }
        return value instanceof List ? "a list" : "an object";
    }

    /**
     * A JSON number as an exact integer when it is one (an integral float reads as that integer,
     * as Python compares it), else the double, else null.
     */
    private static Object number(Object value) {
        BigInteger whole = intInstance(value);
        if (whole != null) {
            return whole;
        }
        if (value instanceof BigDecimal) {
            double number = floating(value);
            if (Double.isFinite(number) && Math.floor(number) == number) {
                return new BigDecimal(number).toBigIntegerExact();
            }
            return number;
        }
        return null;
    }

    /** Exact, as Python compares an int with a float; NaN is unordered and equal to nothing. */
    private static int compareNumbers(Object left, Object right) {
        if (left instanceof Double leftDouble && Double.isNaN(leftDouble)
                || right instanceof Double rightDouble && Double.isNaN(rightDouble)) {
            return 2;
        }
        int leftInfinity = infinity(left);
        int rightInfinity = infinity(right);
        if (leftInfinity != 0 || rightInfinity != 0) {
            return Integer.compare(leftInfinity, rightInfinity);
        }
        return exact(left).compareTo(exact(right));
    }

    private static int infinity(Object number) {
        if (number instanceof Double value && Double.isInfinite(value)) {
            return value > 0 ? 1 : -1;
        }
        return 0;
    }

    private static BigDecimal exact(Object number) {
        return number instanceof BigInteger whole ? new BigDecimal(whole) : new BigDecimal((Double) number);
    }

    /** Python's {@code None} as a dict key. */
    private enum NoneKey {
        NONE
    }

    static boolean sameKey(Object left, Object right) {
        return Objects.equals(hashKey(left), hashKey(right));
    }
}
