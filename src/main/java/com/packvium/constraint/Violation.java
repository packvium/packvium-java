package com.packvium.constraint;

/**
 * A broken rule as {@code [code, detail]}: the pair every load and route check returns.
 *
 * <p>The reference engine passes a two-element array; Java needs a named pair.
 */
public record Violation(String code, String detail) {
}
