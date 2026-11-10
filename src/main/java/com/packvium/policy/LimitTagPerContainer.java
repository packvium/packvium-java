package com.packvium.policy;

/**
 * At most {@code maxCount} items carrying the tag may share one container. Compiles to the
 * tag-count constraint.
 */
public record LimitTagPerContainer(String tag, long maxCount) implements RuleForm {
}
