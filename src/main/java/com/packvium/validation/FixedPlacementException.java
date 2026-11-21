package com.packvium.validation;

import com.packvium.serialization.InvalidRequestException;

/**
 * The request's fixed placements are malformed ({@code reason()} {@code malformed},
 * {@code field()} the bad value) or cannot all hold ({@code cannot_hold}, {@code field()}
 * {@code /fixed_placements}), so no search is attempted. A request error like any other, so
 * catching {@link InvalidRequestException} catches it; its message keeps the
 * {@code invalid_fixed_placement: <detail>} spelling the revisions corpus pins.
 */
public final class FixedPlacementException extends InvalidRequestException {
    private static final long serialVersionUID = 1L;

    public static final String CODE = "invalid_fixed_placement";

    public FixedPlacementException(String detail) {
        this(detail, "cannot_hold", "/fixed_placements");
    }

    public FixedPlacementException(String detail, String reason, String field) {
        super(CODE + ": " + detail, reason, field, detail, null);
    }

    @Override
    public String errorCode() {
        return CODE;
    }
}
