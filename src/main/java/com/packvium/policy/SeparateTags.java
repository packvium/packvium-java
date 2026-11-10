package com.packvium.policy;

/** No container may hold both tags. Compiles to the compatibility constraint. */
public record SeparateTags(String tag, String fromTag) implements RuleForm {
}
