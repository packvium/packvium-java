package com.packvium.validation;

import java.util.List;

/** Every issue the independent validator found, in the order it found them. */
public record ValidationReport(boolean valid, List<ValidationIssue> issues) {
    public ValidationReport {
        issues = List.copyOf(issues);
    }
}
