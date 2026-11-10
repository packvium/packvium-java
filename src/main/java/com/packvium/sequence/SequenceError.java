package com.packvium.sequence;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * No safe removal order exists: every remaining placement is blocked in every allowed
 * direction. Carries the indices still unremovable when the search stopped, not just the first
 * one, since a real deadlock is usually a mutual one.
 */
public final class SequenceError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The stable discriminator carried in {@link #toMap}, shared with the other engines. */
    public static final String ERROR_CODE = "sequence_stuck";

    private final ArrayList<Integer> stuck;

    public SequenceError(List<Integer> stuck) {
        super("no safe order exists: placements " + SequenceIndexes.joined(stuck) + " are mutually blocking");
        this.stuck = new ArrayList<>(stuck);
    }

    public List<Integer> stuck() {
        return Collections.unmodifiableList(stuck);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", ERROR_CODE);
        rendered.put("stuck", SequenceIndexes.sorted(stuck));
        return rendered;
    }
}
