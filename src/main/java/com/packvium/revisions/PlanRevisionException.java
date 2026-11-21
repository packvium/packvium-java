package com.packvium.revisions;

/**
 * A revision could not be built. {@link #errorCode()} is one of a closed set shared by four
 * engines: {@code invalid_revision}, {@code invalid_event}, {@code event_conflict},
 * {@code invalid_artifact}, and the canonical-form codes {@code number_out_of_range},
 * {@code invalid_string}, {@code invalid_value}. A message quotes a value by its canonical JSON,
 * so every engine writes the same text.
 */
public final class PlanRevisionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public PlanRevisionException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public PlanRevisionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
