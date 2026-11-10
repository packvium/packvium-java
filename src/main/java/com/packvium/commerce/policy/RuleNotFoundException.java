package com.packvium.commerce.policy;

/** No rule is registered under the given rule id. */
public final class RuleNotFoundException extends PolicyException {
    private static final long serialVersionUID = 1L;

    private final String ruleId;

    public RuleNotFoundException(String message, String ruleId) {
        super(message);
        this.ruleId = ruleId;
    }

    public String ruleId() {
        return ruleId;
    }
}
