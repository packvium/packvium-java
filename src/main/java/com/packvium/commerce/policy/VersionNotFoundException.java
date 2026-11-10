package com.packvium.commerce.policy;

/** An explicitly referenced rule version number does not exist in that rule's history. */
public final class VersionNotFoundException extends PolicyException {
    private static final long serialVersionUID = 1L;

    private final String ruleId;
    private final long version;

    public VersionNotFoundException(String message, String ruleId, long version) {
        super(message);
        this.ruleId = ruleId;
        this.version = version;
    }

    public String ruleId() {
        return ruleId;
    }

    public long version() {
        return version;
    }
}
