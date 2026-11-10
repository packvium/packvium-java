package com.packvium.validation;

/** One broken guarantee: a stable code and the detail naming what broke it. */
public record ValidationIssue(String code, String detail) {
}
