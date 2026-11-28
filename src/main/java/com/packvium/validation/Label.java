package com.packvium.validation;

/**
 * A packed container's id as an issue prints it. The reference reads {@code packed["id"]} only
 * while formatting an issue, so a missing id fails only once an issue needs it; {@link #verify}
 * reports that after the check, which aborts the run as the reference's {@code KeyError} does.
 */
final class Label {
    private final String text;
    private boolean missingUsed;

    Label(Object packed) {
        text = Json.has(packed, "id") ? PyFormat.str(Json.dict(packed).get("id")) : null;
        if (!Json.isDict(packed)) {
            throw MalformedResult.shape("a packed container must be an object, got " + Json.kind(packed));
        }
    }

    @Override
    public String toString() {
        if (text == null) {
            missingUsed = true;
            return "?";
        }
        return text;
    }

    void verify() {
        if (missingUsed) {
            throw MalformedResult.shape("a packed container with an issue has no id");
        }
    }
}
