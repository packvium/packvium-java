package com.packvium.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The PHP array semantics the reference port's observable behaviour depends on.
 *
 * <p>PHP decodes both JSON objects and JSON arrays into one array type, and turns a
 * canonical decimal-integer object key into an integer key. That is visible on the wire:
 * {@code {}} is accepted where a list belongs, {@code {"0": x}} counts as a list, and a
 * list equals the object spelling its indices. These helpers recreate exactly that over
 * the {@code Map}/{@code List} values {@link com.packvium.serialization.JsonReader} yields,
 * so the Java port answers -- and refuses -- what PHP does.
 */
public final class PhpArrays {
    private static final Pattern INTEGER_KEY = Pattern.compile("0|-?[1-9][0-9]*");

    private PhpArrays() {
    }

    /** Whether PHP stores this object key as an integer rather than a string. */
    public static boolean isIntegerKey(String key) {
        if (!INTEGER_KEY.matcher(key).matches()) {
            return false;
        }
        try {
            Long.parseLong(key);
            return true;
        } catch (NumberFormatException outOfRange) {
            return false;
        }
    }

    public static boolean isArray(Object value) {
        return value instanceof Map<?, ?> || value instanceof List<?>;
    }

    /** PHP's {@code array_is_list}: keys are exactly 0..n-1 in order. O(n). */
    public static boolean isList(Object value) {
        if (value instanceof List<?>) {
            return true;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        long expected = 0;
        for (Object key : map.keySet()) {
            String text = key.toString();
            if (!isIntegerKey(text) || Long.parseLong(text) != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    public static int count(Object array) {
        return array instanceof List<?> list ? list.size() : ((Map<?, ?>) array).size();
    }

    /** The array's keys as PHP holds them: a Long for an integer key, a String otherwise. */
    public static List<Object> keys(Object array) {
        List<Object> keys = new ArrayList<>();
        if (array instanceof List<?> list) {
            for (long index = 0; index < list.size(); index++) {
                keys.add(index);
            }
            return keys;
        }
        for (Object key : ((Map<?, ?>) array).keySet()) {
            String text = key.toString();
            keys.add(isIntegerKey(text) ? (Object) Long.parseLong(text) : text);
        }
        return keys;
    }

    public static List<Object> values(Object array) {
        if (array instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>(((Map<?, ?>) array).values());
    }
}
