package com.packvium.objective;

import java.util.Arrays;
import java.util.List;

/** A lexicographic objective vector; lower is better, compared key by key. */
public record ObjectiveScore(List<Long> components) implements Comparable<ObjectiveScore> {
    public ObjectiveScore {
        components = List.copyOf(components);
    }

    public static ObjectiveScore of(long... components) {
        return new ObjectiveScore(Arrays.stream(components).boxed().toList());
    }

    public long component(int index) {
        return components.get(index);
    }

    @Override
    public int compareTo(ObjectiveScore other) {
        int shared = Math.min(components.size(), other.components.size());
        for (int index = 0; index < shared; index++) {
            int order = Long.compare(components.get(index), other.components.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(components.size(), other.components.size());
    }
}
