package com.packvium.sequence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Whether placement {@code index} could be reached and removed <em>right now</em>, given the full
 * scene as it stands, its contact-graph dependents and the declared route
 * -- a snapshot query: "if the door opened right now, what could actually come out first."
 *
 * <p>{@code reachable} is true only when none of the three blocks it. {@code blockedByNeighbors}
 * is only populated when <em>every</em> allowed direction is blocked -- a single clear direction
 * is enough to leave.
 */
public record Reachability(
        int index,
        boolean reachable,
        Set<Integer> blockedBySupport,
        Set<Integer> blockedByNeighbors,
        Set<Integer> blockedByRoute) {

    public Reachability {
        blockedBySupport = Set.copyOf(blockedBySupport);
        blockedByNeighbors = Set.copyOf(blockedByNeighbors);
        blockedByRoute = Set.copyOf(blockedByRoute);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("index", (long) index);
        rendered.put("reachable", reachable);
        rendered.put("blocked_by_support", SequenceIndexes.sorted(blockedBySupport));
        rendered.put("blocked_by_neighbors", SequenceIndexes.sorted(blockedByNeighbors));
        rendered.put("blocked_by_route", SequenceIndexes.sorted(blockedByRoute));
        return rendered;
    }
}
