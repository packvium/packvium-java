package com.packvium.commerce.policy;

import java.util.Optional;

/** The named eligibility domains this engine understands. */
public enum PolicyScope {
    FACILITY("facility"),
    CUSTOMER("customer"),
    CARRIER("carrier"),
    MATERIAL("material"),
    HAZMAT("hazmat"),
    TEMPERATURE("temperature"),
    SERVICE("service");

    private final String value;

    PolicyScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<PolicyScope> tryFrom(String value) {
        for (PolicyScope candidate : values()) {
            if (candidate.value.equals(value)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
