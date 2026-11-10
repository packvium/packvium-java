package com.packvium.commerce.policy;

import java.util.Optional;

public enum PolicyAction {
    ALLOW("allow"),
    REJECT("reject");

    private final String value;

    PolicyAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<PolicyAction> tryFrom(String value) {
        for (PolicyAction candidate : values()) {
            if (candidate.value.equals(value)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
