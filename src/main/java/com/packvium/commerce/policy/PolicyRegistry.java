package com.packvium.commerce.policy;

import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only history of policy rules, one history per rule id, plus the deterministic
 * conflict resolution over them.
 *
 * <p>Complexity: evaluate() is O(n * (h + p)) over n rule ids, h versions each and p
 * predicates; decide() over an already-resolved set is O(r * p) with no lookup at all.
 */
public final class PolicyRegistry {
    /** Insertion-ordered, so evaluation visits rule ids in publication order as PHP does. */
    private final Map<String, List<Rule>> versions = new LinkedHashMap<>();

    /** One pinned [rule_id, version] pair of an explicit policy snapshot. */
    public record Pin(String ruleId, long version) {
    }

    public Rule publish(String ruleId, PolicyScope scope, PolicyAction action, List<Predicate> predicates,
            long priority, long effectiveAt, String reason) {
        List<Rule> history = versions.getOrDefault(ruleId, List.of());
        Rule rule = new Rule(ruleId, history.size() + 1L, scope, action, predicates, priority, effectiveAt, reason);
        // Registered only once the rule validated, so a refused publish leaves no empty history.
        versions.computeIfAbsent(ruleId, unused -> new ArrayList<>()).add(rule);
        return rule;
    }

    public Rule publish(String ruleId, PolicyScope scope, PolicyAction action, List<Predicate> predicates,
            long priority, long effectiveAt) {
        return publish(ruleId, scope, action, predicates, priority, effectiveAt, "");
    }

    public List<Rule> versions(String ruleId) {
        return List.copyOf(history(ruleId));
    }

    public Rule version(String ruleId, long number) {
        for (Rule rule : history(ruleId)) {
            if (rule.version() == number) {
                return rule;
            }
        }
        throw new VersionNotFoundException("rule '" + ruleId + "' has no version " + number, ruleId, number);
    }

    /**
     * Resolve and deterministically order an explicit policy snapshot. Pins resolve in
     * sorted order, so which missing pin is reported never depends on the caller's order.
     */
    public List<Rule> resolveVersions(List<Pin> pins) {
        HashSet<String> ids = new HashSet<>();
        for (Pin pin : pins) {
            if (!ids.add(pin.ruleId())) {
                throw new IllegalArgumentException("a policy snapshot cannot pin the same rule id twice");
            }
        }
        List<Pin> sorted = new ArrayList<>(pins);
        sorted.sort((left, right) -> left.ruleId().equals(right.ruleId())
                ? Long.compare(left.version(), right.version())
                : SortKey.compareCodePoints(left.ruleId(), right.ruleId()));
        List<Rule> rules = new ArrayList<>(sorted.size());
        for (Pin pin : sorted) {
            rules.add(version(pin.ruleId(), pin.version()));
        }
        return List.copyOf(rules);
    }

    /**
     * One deterministic decision for scope/context as of asOf. Only the version
     * effective as of that instant participates for each rule id.
     */
    public Decision evaluate(PolicyScope scope, Map<String, ?> context, long asOf) {
        List<Rule> matching = new ArrayList<>();
        for (List<Rule> history : versions.values()) {
            Optional<Rule> effective = effective(history, asOf);
            if (effective.isEmpty() || effective.get().scope() != scope) {
                continue;
            }
            if (effective.get().matches(context)) {
                matching.add(effective.get());
            }
        }
        return decide(matching, scope, context);
    }

    /**
     * Evaluate an already-resolved immutable rule set: an explicit REJECT always
     * outranks an ALLOW for the same context, the highest priority wins among equals,
     * and ties break on the lexicographically smallest rule id -- never on iteration
     * order. Nothing matching at all is allowed with no citation (open by default).
     */
    public static Decision decide(List<Rule> rules, PolicyScope scope, Map<String, ?> context) {
        List<Rule> rejects = new ArrayList<>();
        List<Rule> allows = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.scope() != scope || !rule.matches(context)) {
                continue;
            }
            (rule.action() == PolicyAction.REJECT ? rejects : allows).add(rule);
        }
        List<Rule> pool = rejects.isEmpty() ? allows : rejects;
        if (pool.isEmpty()) {
            return new Decision(scope, true, Optional.empty());
        }
        Rule winner = pool.get(0);
        for (Rule rule : pool) {
            if (rule.priority() > winner.priority()
                    || (rule.priority() == winner.priority()
                            && SortKey.compareCodePoints(rule.ruleId(), winner.ruleId()) < 0)) {
                winner = rule;
            }
        }
        Citation citation = new Citation(
                winner.ruleId(), winner.version(), winner.action(), winner.priority(), winner.reason());
        return new Decision(scope, winner.action() == PolicyAction.ALLOW, Optional.of(citation));
    }

    private List<Rule> history(String ruleId) {
        List<Rule> history = versions.get(ruleId);
        if (history == null) {
            throw new RuleNotFoundException("no rule registered under id '" + ruleId + "'", ruleId);
        }
        return history;
    }

    private static Optional<Rule> effective(List<Rule> history, long asOf) {
        Rule winner = null;
        for (Rule rule : history) {
            if (rule.effectiveAt() > asOf) {
                continue;
            }
            if (winner == null
                    || rule.effectiveAt() > winner.effectiveAt()
                    || (rule.effectiveAt() == winner.effectiveAt() && rule.version() > winner.version())) {
                winner = rule;
            }
        }
        return Optional.ofNullable(winner);
    }
}
