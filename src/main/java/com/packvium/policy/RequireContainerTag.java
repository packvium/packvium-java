package com.packvium.policy;

/**
 * An item carrying {@code itemTag} may only enter a container carrying {@code containerTag}.
 * Compiles to the container-eligibility constraint.
 */
public record RequireContainerTag(String itemTag, String containerTag) implements RuleForm {
}
