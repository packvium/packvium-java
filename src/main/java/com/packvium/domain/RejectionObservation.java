package com.packvium.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One reason code observed while trying to place an item, and how often. */
public record RejectionObservation(String code, long count, List<String> details) {
    public RejectionObservation {
        details = List.copyOf(details);
    }

    public RejectionObservation(String code) {
        this(code, 1, List.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", code);
        rendered.put("count", count);
        rendered.put("details", details);
        return rendered;
    }
}
