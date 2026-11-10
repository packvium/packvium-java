package com.packvium.policy;

import com.packvium.support.PhpArrays;
import com.packvium.constraint.PlacementConstraint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules that participate in one request, already resolved and ordered.
 *
 * <p>Resolution is fixed by the contract in docs/POLICY-RULES.md and must be identical in every
 * engine, or the same request packs differently depending on which one answered it.
 */
public record PolicyRuleSet(List<PolicyRule> rules) {
    private static final List<String> KEYS = List.of("as_of", "shipment", "rules");

    public PolicyRuleSet {
        rules = List.copyOf(rules);
    }

    public PolicyRuleSet() {
        this(List.of());
    }

    /**
     * Parses the decoded {@code policy} value: a {@code Map}/{@code List} tree from
     * {@code JsonReader}, or null when the request declares none. O(r log r) for r rules.
     */
    public static PolicyRuleSet fromArray(Object raw) {
        if (raw == null) {
            return new PolicyRuleSet();
        }
        if (!PhpValues.isArray(raw)) {
            throw new PolicyException("policy must be an object");
        }
        List<String> unknown = PhpValues.sorted(
                PhpValues.keys(raw).stream().filter(key -> !KEYS.contains(key)).toList());
        if (!unknown.isEmpty()) {
            throw new PolicyException("policy has unknown keys: " + String.join(", ", unknown));
        }
        Object declared = PhpValues.get(raw, "rules");
        if (declared == null) {
            return new PolicyRuleSet();
        }
        if (!PhpValues.isArray(declared)) {
            throw new PolicyException("policy.rules must be an array");
        }
        if (PhpArrays.count(declared) == 0) {
            return new PolicyRuleSet();
        }
        if (!PhpValues.has(raw, "as_of")) {
            // No default: a guessed instant silently activates or hides a restriction, and
            // reading a clock here would make one request pack differently on different days.
            throw new PolicyException("policy.as_of is required whenever policy.rules is non-empty");
        }
        long asOf = PolicyRule.integer(PhpValues.get(raw, "as_of"), "policy.as_of", 0);
        ShipmentContext shipment = ShipmentContext.fromArray(PhpValues.get(raw, "shipment"), "policy.shipment");
        List<PolicyRule> parsed = new ArrayList<>();
        List<Object> values = PhpArrays.values(declared);
        for (int index = 0; index < values.size(); index++) {
            parsed.add(PolicyRule.fromArray(values.get(index), index));
        }
        return new PolicyRuleSet(resolve(parsed, asOf, shipment));
    }

    private static List<PolicyRule> resolve(List<PolicyRule> rules, long asOf, ShipmentContext shipment) {
        // Append-only per id: among participating versions of one id the highest `effectiveAt`
        // wins, ties broken by the highest `version`. The same resolution the catalog registry
        // already uses for `as_of` lookups, deliberately, so a reader learns one rule and not two.
        Map<String, PolicyRule> latest = new LinkedHashMap<>();
        for (PolicyRule rule : rules) {
            if (rule.effectiveAt() > asOf || !rule.appliesTo().satisfiedBy(shipment)) {
                continue;
            }
            PolicyRule current = latest.get(rule.id());
            if (current == null || isLater(rule, current)) {
                latest.put(rule.id(), rule);
            }
        }
        List<PolicyRule> resolved = new ArrayList<>(latest.values());
        // Citation order, not evaluation order: the first rule that rejects a candidate is the
        // one cited, so sorting here is what makes the citation deterministic. Ties go to the
        // smallest id -- never to insertion order, which would follow the order the caller
        // happened to write.
        resolved.sort((a, b) -> {
            int byPriority = Long.compare(b.priority(), a.priority());
            return byPriority != 0 ? byPriority : PhpValues.compare(a.id(), b.id());
        });
        return resolved;
    }

    private static boolean isLater(PolicyRule rule, PolicyRule current) {
        if (rule.effectiveAt() != current.effectiveAt()) {
            return rule.effectiveAt() > current.effectiveAt();
        }
        return rule.version() > current.version();
    }

    /**
     * Typed as the pipeline's own constraint list, which is where it is spliced in; the one
     * element is always a {@link PolicyConstraint}.
     */
    public List<PlacementConstraint> constraints() {
        return rules.isEmpty() ? List.of() : List.of(new PolicyConstraint(rules));
    }
}
