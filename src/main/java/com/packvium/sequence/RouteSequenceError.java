package com.packvium.sequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * No safe order exists that fully empties {@code stop} before the route moves on to the next
 * one. Distinct from SequenceError: that one means no <em>unconstrained</em> order
 * exists at all; this means the stop-by-stop order the route itself demands does not.
 */
public final class RouteSequenceError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long stop;
    private final ArrayList<Integer> stuck;

    public RouteSequenceError(long stop, List<Integer> stuck) {
        super("stop " + stop + ": placements " + SequenceIndexes.joined(stuck) + " cannot be unloaded there");
        this.stop = stop;
        this.stuck = new ArrayList<>(stuck);
    }

    public long stop() {
        return stop;
    }

    public List<Integer> stuck() {
        return Collections.unmodifiableList(stuck);
    }
}
