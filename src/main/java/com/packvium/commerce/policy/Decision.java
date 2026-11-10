package com.packvium.commerce.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The outcome of one evaluation: whether the context is admitted for the given scope,
 * and the single rule (if any) whose citation explains why.
 */
public record Decision(PolicyScope scope, boolean allowed, Optional<Citation> citation) {
    public Decision {
        if (!allowed && citation.isEmpty()) {
            throw new IllegalArgumentException("a REJECT decision must carry a citation");
        }
    }

    public Map<String, Object> toArray() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", scope.value());
        fields.put("allowed", allowed);
        fields.put("citation", citation.map(Citation::toArray).orElse(null));
        return fields;
    }
}
