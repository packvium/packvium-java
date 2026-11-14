package com.packvium.simulation;

/**
 * One policy rule pinned to one version inside a {@link ScenarioVersionPin}.
 *
 * @param ruleId the policy rule's id
 * @param version the rule's version number in its append-only history
 */
public record PolicyVersionPin(String ruleId, long version) {
}
