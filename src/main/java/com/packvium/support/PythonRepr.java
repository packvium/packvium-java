package com.packvium.support;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Python's {@code repr()} of the few value shapes the Python engine's diagnostics interpolate.
 *
 * <p>The lock, Pareto, outcome and holdout modules exist only in Python at the tag, and their
 * error text is part of what this port reproduces: a caller matching on a message, or an operator
 * comparing two engines' diagnostics, must read the same characters. Those messages are f-strings
 * over a string ({@code {x!r}}), a list of strings ({@code {sorted(names)}}) or a tuple of ticks
 * ({@code {position_ticks}}); the canonical writer's out-of-range refusal quotes a float
 * ({@code {value!r}}).
 *
 * <p>Strings follow CPython's quoting rule (single quotes unless the text holds a single quote and
 * no double quote) and escape the backslash, the quote in use, {@code \n}, {@code \r}, {@code \t}
 * and the C0/C1 control characters as {@code \xNN}. CPython also escapes other non-printable code
 * points (unassigned, format and separator characters); none occurs in an identifier this port
 * renders, and they are emitted raw.
 */
public final class PythonRepr {
    private PythonRepr() {
    }

    /** {@code repr(text)}. O(n) in the length of the text. */
    public static String string(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        text.codePoints().forEach(point -> appendEscaped(out, point, quote));
        return out.append(quote).toString();
    }

    /** {@code repr(list_of_strings)}: {@code ['a', 'b']}, or {@code []}. */
    public static String strings(List<String> values) {
        return values.stream().map(PythonRepr::string).collect(Collectors.joining(", ", "[", "]"));
    }

    /** {@code repr(tuple_of_ints)}: {@code (0, 0, 0)}, with Python's trailing comma for one element. */
    public static String integers(List<Long> values) {
        if (values.size() == 1) {
            return "(" + values.get(0) + ",)";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * {@code repr(float)}: the shortest round-trip digits, fixed between 1e-4 and 1e16 (with
     * {@code .0} when integral), otherwise scientific with a signed exponent of at least two
     * digits: {@code 1e+300}, {@code 9007199254740994.0}, {@code inf}.
     */
    public static String floating(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        if (value == 0) {
            return 1 / value < 0 ? "-0.0" : "0.0";
        }
        ShortestFloat.Digits shortest = ShortestFloat.digits(Math.abs(value));
        String digits = shortest.digits();
        int point = shortest.point();
        int exponent = point - 1;
        String rendered;
        if (exponent < -4 || exponent >= 16) {
            rendered = digits.charAt(0) + (digits.length() > 1 ? "." + digits.substring(1) : "") + "e"
                    + (exponent < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        } else if (point >= digits.length()) {
            rendered = digits + "0".repeat(point - digits.length()) + ".0";
        } else if (point > 0) {
            rendered = digits.substring(0, point) + "." + digits.substring(point);
        } else {
            rendered = "0." + "0".repeat(-point) + digits;
        }
        return (value < 0 ? "-" : "") + rendered;
    }

    private static void appendEscaped(StringBuilder out, int point, char quote) {
        switch (point) {
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (point == quote) {
                    out.append('\\').append(quote);
                } else if (point < 0x20 || (point >= 0x7f && point <= 0x9f)) {
                    out.append(String.format(Locale.ROOT, "\\x%02x", point));
                } else {
                    out.appendCodePoint(point);
                }
            }
        }
    }
}
