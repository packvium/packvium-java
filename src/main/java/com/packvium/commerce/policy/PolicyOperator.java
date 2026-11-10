package com.packvium.commerce.policy;

import java.util.Optional;

/** The closed set of predicate comparisons. Anything outside it fails admission. */
public enum PolicyOperator {
    EQUALS("equals"),
    NOT_EQUALS("not_equals"),
    IN("in"),
    NOT_IN("not_in"),
    EXISTS("exists"),
    ABSENT("absent");

    private final String value;

    PolicyOperator(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<PolicyOperator> tryFrom(String value) {
        for (PolicyOperator candidate : values()) {
            if (candidate.value.equals(value)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
