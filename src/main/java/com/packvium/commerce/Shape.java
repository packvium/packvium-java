package com.packvium.commerce;

import com.packvium.commerce.catalog.CatalogException;
import com.packvium.support.PhpArrays;
import com.packvium.commerce.policy.PolicyException;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shape validation for the commerce document and its requests.
 *
 * <p>Every failure is reported with the JSON path that caused it, so a caller fixes the
 * field rather than bisecting the payload. Nothing here interprets a value's meaning --
 * the models keep the one definition of what is valid, and model() only re-labels their
 * own validation for the API boundary.
 *
 * <p>Container checks follow PHP's single array type (see {@link PhpArrays}): an empty
 * object or list passes as either, and an object keyed 0..n-1 is a list.
 */
public final class Shape {
    private Shape() {
    }

    /**
     * Always throws; the declared return type lets a caller write {@code throw Shape.fail(...)}
     * so the compiler sees the branch end, as PHP's {@code never} does.
     */
    public static CommerceInputException fail(String path, String message) {
        throw new CommerceInputException(path + ": " + message);
    }

    public static Map<String, Object> map(Object value, String path) {
        if (value instanceof List<?> list && list.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> object) || (!object.isEmpty() && PhpArrays.isList(object))) {
            throw fail(path, "expected an object");
        }
        Map<String, Object> members = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : object.entrySet()) {
            members.put(member.getKey().toString(), member.getValue());
        }
        return members;
    }

    public static List<Object> listOf(Object value, String path) {
        if (!PhpArrays.isList(value)) {
            throw fail(path, "expected a list");
        }
        return PhpArrays.values(value);
    }

    public static long integer(Object value, String path) {
        // A JSON boolean where an exact integer belongs is a caller mistake, not a 0 or 1;
        // a number past 64 bits is a float to the reference port and refused the same way.
        if (!(value instanceof Long number)) {
            throw fail(path, "expected an exact integer");
        }
        return number;
    }

    public static String text(Object value, String path) {
        if (!(value instanceof String string)) {
            throw fail(path, "expected a string");
        }
        return string;
    }

    /** Required keys first, then unknown ones, each reported in code-point order. O(k log k). */
    public static void keys(Map<String, ?> value, String path, List<String> required, List<String> optional) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (!value.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(SortKey::compareCodePoints);
            throw fail(path, "missing required key(s) [" + render(missing) + "]");
        }
        List<String> unknown = new ArrayList<>();
        for (String name : value.keySet()) {
            if (!required.contains(name) && !optional.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(SortKey::compareCodePoints);
            throw fail(path, "unrecognised key(s) [" + render(unknown) + "]");
        }
    }

    public static void keys(Map<String, ?> value, String path, List<String> required) {
        keys(value, path, required, List.of());
    }

    public static List<Long> axes(Object value, String path, int axes) {
        List<Object> entries = listOf(value, path);
        if (entries.size() != axes) {
            throw fail(path, "expected exactly " + axes + " axes");
        }
        List<Long> parsed = new ArrayList<>(axes);
        for (int index = 0; index < entries.size(); index++) {
            parsed.add(integer(entries.get(index), path + "[" + index + "]"));
        }
        return parsed;
    }

    public static <E extends Enum<E>> E enumCase(Function<String, Optional<E>> tryFrom, Object value, String path) {
        String text = text(value, path);
        return tryFrom.apply(text).orElseThrow(() -> fail(path, "unsupported value '" + text + "'"));
    }

    /** Run a model constructor, reporting its own validation as an input error. */
    public static <T> T model(String path, Supplier<T> build) {
        try {
            return build.get();
        } catch (CommerceInputException error) {
            throw error;
        } catch (IllegalArgumentException | PolicyException | CatalogException error) {
            throw new CommerceInputException(path + ": " + error.getMessage(), error);
        }
    }

    /** PHP's {@code $fields[$key] ?? $fallback}: an explicit null counts as absent. */
    static Object orDefault(Map<String, ?> fields, String key, Object fallback) {
        Object value = fields.get(key);
        return value == null ? fallback : value;
    }

    private static String render(List<String> values) {
        StringJoiner joined = new StringJoiner(", ");
        for (String value : values) {
            joined.add("'" + value + "'");
        }
        return joined.toString();
    }
}
