package com.packvium.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An insertion-ordered map keyed the way a Python dict is, by {@link Json#hashKey}: {@code 1} and
 * {@code True} are one key, and assigning an existing key replaces its value in place. Keys keep
 * the JSON value they came from, for printing.
 *
 * @param <T> the values
 */
final class Ordered<T> {
    private final List<Object> keys = new ArrayList<>();
    private final List<T> values = new ArrayList<>();
    private final Map<Object, Integer> index = new HashMap<>();

    void set(Object key, T value) {
        Object hashed = Json.hashKey(key);
        Integer position = index.get(hashed);
        if (position != null) {
            values.set(position, value);
            return;
        }
        index.put(hashed, keys.size());
        keys.add(key);
        values.add(value);
    }

    T get(Object key) {
        Integer position = index.get(Json.hashKey(key));
        return position == null ? null : values.get(position);
    }

    T get(Object key, T fallback) {
        T value = get(key);
        return value == null ? fallback : value;
    }

    boolean has(Object key) {
        return index.containsKey(Json.hashKey(key));
    }

    /** The entry's place in insertion order, or -1. */
    int position(Object key) {
        Integer position = index.get(Json.hashKey(key));
        return position == null ? -1 : position;
    }

    List<Object> keys() {
        return keys;
    }

    List<T> values() {
        return values;
    }

    int size() {
        return keys.size();
    }
}
