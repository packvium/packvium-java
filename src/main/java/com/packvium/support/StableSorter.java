package com.packvium.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Stable sort by a key built once per element, mirroring Python's {@code sorted(key=...)}. */
public final class StableSorter {
    private StableSorter() {
    }

    /** O(n log n) comparisons, O(n) keys. Equal keys keep input order. */
    public static <T> List<T> sortBy(List<T> values, Function<T, SortKey> key) {
        List<Keyed<T>> decorated = new ArrayList<>(values.size());
        for (T value : values) {
            decorated.add(new Keyed<>(key.apply(value), value));
        }
        // List.sort is a stable merge sort, so ties keep their input order.
        decorated.sort(Comparator.comparing(Keyed::key));
        List<T> sorted = new ArrayList<>(decorated.size());
        for (Keyed<T> entry : decorated) {
            sorted.add(entry.value());
        }
        return sorted;
    }

    private record Keyed<T>(SortKey key, T value) {
    }
}
