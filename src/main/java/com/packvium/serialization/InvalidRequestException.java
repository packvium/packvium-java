package com.packvium.serialization;

/**
 * The request is not one any engine may answer. Nothing was solved.
 *
 * <p>The same refusal every Packvium engine raises. A caller branches on
 * {@link #errorCode()} ({@code invalid_request}; subclasses such as
 * {@link com.packvium.validation.FixedPlacementException} name their own), on {@link #reason()} --
 * one of a closed set shared by every Packvium engine: {@code missing_field}, {@code wrong_type},
 * {@code below_minimum}, {@code above_maximum}, {@code negative_measure}, {@code invalid_unit},
 * {@code duplicate_id}, {@code not_allowed}, {@code invalid_value} -- and on {@link #field()}, the
 * RFC 6901 JSON Pointer of the offending value ({@code ""} for the request as a whole), so no
 * caller has to parse prose. An {@link IllegalArgumentException}, as every refusal before 1.4 was.
 */
public class InvalidRequestException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private static final String CODE = "invalid_request";

    private final String reason;
    private final String field;
    private final String detail;

    public InvalidRequestException(String reason, String field, String detail) {
        this(reason, field, detail, null);
    }

    public InvalidRequestException(String reason, String field, String detail, Throwable cause) {
        this(compose(CODE, field, detail), reason, field, detail, cause);
    }

    /** For a subclass naming its own code, and so spelling its own message. */
    protected InvalidRequestException(String message, String reason, String field, String detail, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.field = field;
        this.detail = detail;
    }

    public String errorCode() {
        return CODE;
    }

    public String reason() {
        return reason;
    }

    public String field() {
        return field;
    }

    public String detail() {
        return detail;
    }

    /** The message every engine prints for this error, so the prose agrees too. */
    private static String compose(String code, String field, String detail) {
        return field.isEmpty() ? code + ": " + detail : code + ": " + field + ": " + detail;
    }
}
