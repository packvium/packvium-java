package com.packvium.result;

public enum PackingStatus {
    OPTIMAL("optimal"),
    FEASIBLE("feasible"),
    BEST_FOUND("best_found"),
    TIME_LIMIT("time_limit"),
    INFEASIBLE("infeasible"),
    INVALID_RESULT("invalid_result");

    private final String wireName;

    PackingStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
