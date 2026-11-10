package com.packvium.commerce.catalog;

/** A first-party rule forbidding one master-data entry from being packed with another. */
public record ExclusionRule(String id, ExclusionScope scope, String subjectId, String excludedId, String reason) {
    public ExclusionRule {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("exclusion id is required");
        }
        if (subjectId.isEmpty() || excludedId.isEmpty()) {
            throw new IllegalArgumentException("an exclusion rule must reference both a subject and an excluded id");
        }
    }

    public ExclusionRule(String id, ExclusionScope scope, String subjectId, String excludedId) {
        this(id, scope, subjectId, excludedId, "");
    }
}
