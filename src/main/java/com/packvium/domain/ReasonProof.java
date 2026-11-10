package com.packvium.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How strongly an unpacked reason is established, with the observations behind it. */
public record ReasonProof(String level, List<RejectionObservation> observations) {
    private static final List<String> PROVEN = List.of(
            "no_compatible_container_dimensions", "payload_exceeded", "no_eligible_container",
            "rotation_restricted", "policy_rule");
    private static final List<String> LIMITED = List.of("time_limit", "effort_limit");
    private static final List<String> OBSERVED = List.of(
            "no_feasible_placement", "search_exhausted", "exact_search_incomplete", "insufficient_support");

    public ReasonProof {
        observations = List.copyOf(observations);
    }

    public static ReasonProof forReason(String reason) {
        return forReason(reason, List.of());
    }

    public static ReasonProof forReason(String reason, List<String> details) {
        // `policy_rule` is proven, not inferred: it is only ever reported when a rule
        // rules the item out of *every* offered container, which is a statement about
        // the request that no search outcome can change.
        String level;
        if (PROVEN.contains(reason)) {
            level = "proven";
        } else if (LIMITED.contains(reason)) {
            level = "unknown_due_to_limit";
        } else if (OBSERVED.contains(reason)) {
            level = "observed";
        } else {
            level = "inferred";
        }
        return new ReasonProof(level, List.of(new RejectionObservation(reason, 1, details)));
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> rendered = new ArrayList<>(observations.size());
        for (RejectionObservation observation : observations) {
            rendered.add(observation.toMap());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("observations", List.copyOf(rendered));
        return map;
    }
}
