package com.packvium.policy;

import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.ProvenRejection;
import com.packvium.constraint.Violation;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects a candidate placement that any participating rule forbids.
 *
 * <p>{@code O(m + r)} per candidate for {@code m} placements already in the container and
 * {@code r} resolved rules: one pass collecting the tags present, then one pass over the rules.
 * That is the same bound class as the built-in compatibility and tag-count constraints the
 * forms compile onto, so the published complexity bounds are unchanged.
 *
 * <p>Rules arrive in citation order, so the first rejection is already the one the contract
 * says to cite -- highest priority, ties to the smallest id.
 */
public record PolicyConstraint(List<PolicyRule> rules) implements PlacementConstraint, ProvenRejection {
    public PolicyConstraint {
        rules = List.copyOf(rules);
    }

    @Override
    public ConstraintResult evaluate(ConstraintContext c) {
        List<String> itemTags = c.item().item().tags();
        List<String> containerTags = c.container().tags();
        Map<String, Long> present = null;
        for (PolicyRule rule : rules) {
            RuleForm form = rule.form();
            if (form instanceof RequireContainerTag require) {
                if (itemTags.contains(require.itemTag()) && !containerTags.contains(require.containerTag())) {
                    return reject(rule, "requires a container tagged '" + require.containerTag() + "'");
                }
                continue;
            }
            // Both remaining forms need to know what is already in the container, so the walk
            // happens once and only when a rule of that kind exists at all. Only looked up,
            // never iterated, so its order cannot reach output.
            if (present == null) {
                present = tagCounts(c.placements());
            }
            if (form instanceof SeparateTags separate) {
                if (itemTags.contains(separate.tag()) && present.getOrDefault(separate.fromTag(), 0L) > 0) {
                    return reject(rule, "'" + separate.tag() + "' may not share a container with '"
                            + separate.fromTag() + "'");
                }
                if (itemTags.contains(separate.fromTag()) && present.getOrDefault(separate.tag(), 0L) > 0) {
                    return reject(rule, "'" + separate.fromTag() + "' may not share a container with '"
                            + separate.tag() + "'");
                }
            } else if (form instanceof LimitTagPerContainer limit
                    && itemTags.contains(limit.tag())
                    && present.getOrDefault(limit.tag(), 0L) >= limit.maxCount()) {
                return reject(rule, "at most " + limit.maxCount() + " item(s) tagged '" + limit.tag()
                        + "' per container");
            }
        }
        return ConstraintResult.allow();
    }

    /**
     * The rule that rules this item out of every offered container, if one does.
     *
     * <p>Only {@code require_container_tag} can be answered here, and that is not a gap. It is a
     * statement about the request alone -- this item carries the tag, no offered container
     * carries the one it requires -- so it holds however the search goes. Segregation and
     * per-container caps depend on what else was packed, so an item they leave behind was left
     * behind by the search, and reporting that as proven would claim more than the engine knows.
     *
     * <p>{@code O(r * c)} for {@code r} rules and {@code c} containers, once per unpacked item
     * rather than per candidate.
     */
    @Override
    public Optional<Violation> provesUnplaceable(ItemInstance item, List<Container> containers) {
        for (PolicyRule rule : rules) {
            if (!(rule.form() instanceof RequireContainerTag require)) {
                continue;
            }
            if (!item.item().tags().contains(require.itemTag())) {
                continue;
            }
            boolean offered = containers.stream().anyMatch(c -> c.tags().contains(require.containerTag()));
            if (offered) {
                continue;
            }
            return Optional.of(new Violation(
                    "policy_rule",
                    rule.citation() + ": requires a container tagged '" + require.containerTag() + "', "
                            + "which none of the containers offered carries"));
        }
        return Optional.empty();
    }

    private static ConstraintResult reject(PolicyRule rule, String why) {
        return ConstraintResult.reject("policy_rule", rule.citation() + ": " + why);
    }

    private static Map<String, Long> tagCounts(List<Placement> placements) {
        Map<String, Long> counts = new HashMap<>();
        for (Placement placement : placements) {
            for (String tag : placement.instance().item().tags()) {
                counts.merge(tag, 1L, Long::sum);
            }
        }
        return counts;
    }
}
