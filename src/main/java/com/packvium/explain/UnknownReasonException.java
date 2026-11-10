package com.packvium.explain;

/**
 * A reason code with no registered message -- thrown here, at the boundary, rather than
 * letting a missing map key pass silently or surface as an unrelated error deep inside a
 * renderer.
 */
public final class UnknownReasonException extends IndexOutOfBoundsException {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public UnknownReasonException(String reason) {
        super("no explanation registered for reason code '" + reason + "'");
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
