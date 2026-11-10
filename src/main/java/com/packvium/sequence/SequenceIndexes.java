package com.packvium.sequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Index-set renderings shared by the sequence evidence types. */
final class SequenceIndexes {
    private SequenceIndexes() {
    }

    /** Ascending, as wire longs: the evidence is a set, so its insertion order must not leak. */
    static List<Long> sorted(Collection<Integer> indexes) {
        List<Long> out = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            out.add((long) index);
        }
        out.sort(null);
        return List.copyOf(out);
    }

    static String joined(Collection<Integer> indexes) {
        List<String> parts = new ArrayList<>();
        for (long index : sorted(indexes)) {
            parts.add(Long.toString(index));
        }
        return String.join(",", parts);
    }
}
