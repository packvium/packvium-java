package com.packvium.policy;

/**
 * What a rule forbids. Exactly three, each compiling to a constraint the pipeline already runs,
 * because the tag fields already express the predicates -- a rule adds identity, dating and
 * priority, not a second vocabulary.
 */
public sealed interface RuleForm permits SeparateTags, RequireContainerTag, LimitTagPerContainer {
}
