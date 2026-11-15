package com.packvium.artifacts;

/**
 * The builder or an exporter was handed something an artifact cannot carry.
 *
 * <p>{@link #errorCode()} is one of a closed set shared by all four reference engines:
 * {@code invalid_request}, {@code invalid_result}, {@code invalid_plan_input},
 * {@code mixed_units}, {@code number_out_of_range}, {@code invalid_string},
 * {@code invalid_value}, {@code unknown_format}, {@code invalid_json}.
 */
public final class OperationalArtifactException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public OperationalArtifactException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OperationalArtifactException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
