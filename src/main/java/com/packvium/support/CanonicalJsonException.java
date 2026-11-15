package com.packvium.support;

/**
 * A value has no canonical spelling. {@link #errorCode()} names which rule it broke:
 * {@code number_out_of_range}, {@code invalid_string} or {@code invalid_value}.
 */
public final class CanonicalJsonException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public CanonicalJsonException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
