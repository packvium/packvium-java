package com.packvium.commerce.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/** The evidence a decision cites: which rule id/version produced it, and why. */
public record Citation(String ruleId, long version, PolicyAction action, long priority, String reason) {
    public Map<String, Object> toArray() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rule_id", ruleId);
        fields.put("version", version);
        fields.put("action", action.value());
        fields.put("priority", priority);
        fields.put("reason", reason);
        return fields;
    }
}
