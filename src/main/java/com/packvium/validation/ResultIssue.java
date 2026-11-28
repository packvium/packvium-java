package com.packvium.validation;

import java.util.Objects;

/**
 * One way a result fails to be a sound answer to its request, as {@link ResultValidator#validate}
 * reports it: the same code and detail every Packvium package reports for the same documents.
 *
 * @param code the issue's stable code, such as {@code collision} or {@code score_mismatch}
 * @param detail what is wrong, naming the items, containers and values involved
 */
public record ResultIssue(String code, String detail) {
    public ResultIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }

    /** {@code code: detail}. */
    @Override
    public String toString() {
        return code + ": " + detail;
    }
}
