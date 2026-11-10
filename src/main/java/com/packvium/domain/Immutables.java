package com.packvium.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Defensive copies that keep insertion order, which reaches the output and must not drift. */
final class Immutables {
    private Immutables() {
    }

    static <K, V> Map<K, V> orderedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** First occurrence wins, matching the reference engines' de-duplication of tag lists. */
    static List<String> distinct(List<String> values) {
        return List.copyOf(new ArrayList<>(new LinkedHashSet<>(values)));
    }
}
