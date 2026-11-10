package com.packvium.sequence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A given order includes a step that is not feasible against only the placements not yet
 * removed at that point. Distinct from SequenceError (no order exists at all): this
 * means the specific order supplied is wrong, whether or not some other order would work.
 */
public final class SequenceReplayError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "sequence_replay";

    private final long index;
    private final long step;
    private final String reason;

    public SequenceReplayError(long index, long step, String reason) {
        super("step " + step + ": placement " + index + " is not safe there (" + reason + ")");
        this.index = index;
        this.step = step;
        this.reason = reason;
    }

    public long index() {
        return index;
    }

    public long step() {
        return step;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("code", ERROR_CODE);
        rendered.put("index", index);
        rendered.put("step", step);
        rendered.put("reason", reason);
        return rendered;
    }
}
