package com.packvium.sequence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A direction outside the six-value vocabulary was supplied. Rejected rather than silently
 * treated as one of the six -- {@code -z} in particular, since that was this module's own
 * previous (wrong) default for anything unrecognised.
 */
public final class InvalidDirectionError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "invalid_direction";

    private final String direction;

    public InvalidDirectionError(String direction) {
        super("unknown movement direction '" + direction + "'; expected one of +x, -x, +y, -y, +z, -z");
        this.direction = direction;
    }

    public String direction() {
        return direction;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", ERROR_CODE);
        rendered.put("direction", direction);
        return rendered;
    }
}
