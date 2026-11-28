package com.packvium.support;

import com.packvium.serialization.InvalidRequestException;

/**
 * A model refused a value while being built on its own, before it is part of a request, so there
 * is no field to name; {@link com.packvium.Packer#pack} names the field for what it checks itself.
 *
 * <p>The {@link #reason()} is one of the request schema's reasons ({@code below_minimum},
 * {@code invalid_value}, {@code not_allowed}, ...), so a caller building a request from objects
 * branches on it as on any request error. Its message is the model's own text, the one it has
 * always thrown. A JSON request whose rules did not name the value is refused as
 * {@code invalid_value} with this text instead, the form every engine shares.
 */
public final class ObjectRefusal extends InvalidRequestException {
    private static final long serialVersionUID = 1L;

    public ObjectRefusal(String reason, String detail) {
        super(detail, reason, "", detail, null);
    }

    /**
     * The refusal of a ratio outside [0, 1]: below and above are told apart, and anything else
     * outside the range (NaN) is still refused.
     */
    public static ObjectRefusal ratio(double value, String detail) {
        String reason = value < 0 ? "below_minimum" : value > 1 ? "above_maximum" : "invalid_value";
        return new ObjectRefusal(reason, detail);
    }

    /** Whether {@code value} lies in [0, 1]; NaN does not. */
    public static boolean isRatio(double value) {
        return value >= 0 && value <= 1;
    }
}
