package com.packvium.sequence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One step of a generated order, carrying the evidence alongside the bare index: which other
 * placements this step structurally depended on (support), and which direction its
 * accessibility check actually used.
 */
public record SequenceStep(int index, String direction, Set<Integer> dependsOn) {
    public SequenceStep {
        dependsOn = Set.copyOf(dependsOn);
    }

    /** {@code depends_on} as a sorted list, matching the shared sequence fixtures. */
    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("index", (long) index);
        rendered.put("direction", direction);
        rendered.put("depends_on", SequenceIndexes.sorted(dependsOn));
        return rendered;
    }
}
