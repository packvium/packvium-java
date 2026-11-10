package com.packvium.sequence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered, immutable copies of the dependency maps both graphs carry. */
final class DependencyMaps {
    private DependencyMaps() {
    }

    static Map<Integer, Set<Integer>> frozen(Map<Integer, Set<Integer>> source) {
        Map<Integer, Set<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** The order is a permutation of {@code 0..count-1}, each index exactly once. */
    static void requirePermutation(List<Integer> order, int count) {
        List<Integer> sorted = new java.util.ArrayList<>(order);
        sorted.sort(null);
        boolean permutation = sorted.size() == count;
        for (int index = 0; permutation && index < count; index++) {
            permutation = sorted.get(index) == index;
        }
        if (!permutation) {
            throw new SequenceReplayError(-1, -1, "order is not a permutation of every placement index exactly once");
        }
    }

    static Set<Integer> allIndexes(int count) {
        Set<Integer> present = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            present.add(index);
        }
        return present;
    }
}
