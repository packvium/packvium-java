package com.packvium.policy;

import com.packvium.support.PhpArrays;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The PHP value semantics the reference port's policy parsing exposes on the wire.
 *
 * <p>PHP decodes a JSON list and a JSON object into one array type, so a list is accepted where
 * an object belongs and its indices count as keys; sorted key lists in messages use PHP's
 * standard comparison, where two numeric strings compare as numbers. Reproducing both here
 * keeps this engine refusing -- and ordering -- what PHP does.
 */
public final class PhpValues {
    private static final Pattern NUMERIC = Pattern.compile(
            "[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");
    private static final Pattern INTEGER = Pattern.compile("[ \\t\\n\\r\\x0B\\f]*[+-]?\\d+[ \\t\\n\\r\\x0B\\f]*");

    private PhpValues() {
    }

    static boolean isArray(Object value) {
        return PhpArrays.isArray(value);
    }

    /** The array's keys as the strings PHP's {@code array_diff} compares. O(n). */
    static List<String> keys(Object array) {
        List<String> keys = new ArrayList<>();
        for (Object key : PhpArrays.keys(array)) {
            keys.add(key.toString());
        }
        return keys;
    }

    /** {@code array_key_exists} for a non-integer name: a list never has one. */
    static boolean has(Object array, String name) {
        return array instanceof Map<?, ?> map && map.containsKey(name);
    }

    /** {@code $array[$name] ?? null}. */
    static Object get(Object array, String name) {
        return array instanceof Map<?, ?> map ? map.get(name) : null;
    }

    /** PHP's {@code is_int} over a decoded JSON value: an integer past 64 bits decodes to a float. */
    static boolean isInt(Object value) {
        return value instanceof Long || value instanceof Integer;
    }

    /** PHP 8's {@code <=>} on two strings: numerically when both are numeric, else bytewise. */
    public static int compare(String left, String right) {
        if (NUMERIC.matcher(left).matches() && NUMERIC.matcher(right).matches()) {
            if (INTEGER.matcher(left).matches() && INTEGER.matcher(right).matches()) {
                try {
                    return Long.compare(Long.parseLong(strip(left)), Long.parseLong(strip(right)));
                } catch (NumberFormatException overflow) {
                    // Past 64 bits PHP compares the two as doubles, as below.
                }
            }
            double a = Double.parseDouble(strip(left));
            double b = Double.parseDouble(strip(right));
            return a < b ? -1 : (a > b ? 1 : 0);
        }
        // UTF-8 byte order is code point order.
        return SortKey.compareCodePoints(left, right);
    }

    public static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(PhpValues::compare);
        return copy;
    }

    private static String strip(String value) {
        String trimmed = value.strip();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }
}
