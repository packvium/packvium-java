package com.packvium.config;

public enum SolverProfile {
    FAST("fast"),
    BALANCED("balanced"),
    QUALITY("quality"),
    EXACT_SMALL("exact_small");

    private final String wireName;

    SolverProfile(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SolverProfile fromWireName(String wireName) {
        for (SolverProfile profile : values()) {
            if (profile.wireName.equals(wireName)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("\"" + wireName + "\" is not a valid backing value for enum SolverProfile");
    }
}
