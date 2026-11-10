package com.packvium.constraint;

public record ConstraintResult(boolean allowed, String code, String detail) {
    private static final ConstraintResult ALLOWED = new ConstraintResult(true, "allowed", "");

    public static ConstraintResult allow() {
        return ALLOWED;
    }

    public static ConstraintResult reject(String code) {
        return reject(code, "");
    }

    public static ConstraintResult reject(String code, String detail) {
        return new ConstraintResult(false, code, detail);
    }

    static ConstraintResult of(java.util.Optional<Violation> failure) {
        return failure.map(violation -> reject(violation.code(), violation.detail())).orElse(ALLOWED);
    }
}
