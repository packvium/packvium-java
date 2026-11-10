package com.packvium.commerce.policy;

import java.util.List;
import java.util.Map;

/**
 * One immutable, numbered version of one rule id's history. All of a rule's predicates
 * must match (logical AND) for the rule to apply.
 */
public record Rule(String ruleId, long version, PolicyScope scope, PolicyAction action, List<Predicate> predicates,
        long priority, long effectiveAt, String reason) {
    public Rule {
        if (ruleId.isEmpty()) {
            throw new IllegalArgumentException("rule_id is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        predicates = List.copyOf(predicates);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("a rule must have at least one predicate");
        }
        if (effectiveAt < 0) {
            throw new IllegalArgumentException("effective_at cannot be negative");
        }
        for (Predicate predicate : predicates) {
            if (predicate.scope() != scope) {
                throw new IllegalArgumentException(
                        "every predicate of rule '" + ruleId + "' must share the rule's own scope");
            }
        }
    }

    public boolean matches(Map<String, ?> context) {
        for (Predicate predicate : predicates) {
            if (!predicate.matches(context)) {
                return false;
            }
        }
        return true;
    }
}
