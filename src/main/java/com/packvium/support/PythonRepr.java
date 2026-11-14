package com.packvium.support;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Python's {@code repr()} of the few value shapes the reference's diagnostics interpolate.
 *
 * <p>The lock, Pareto, outcome and holdout modules exist only in Python at the tag, and their
 * error text is part of what this port reproduces: a caller matching on a message, or an operator
 * comparing two engines' diagnostics, must read the same characters. Those messages are f-strings
 * over a string ({@code {x!r}}), a list of strings ({@code {sorted(names)}}) or a tuple of ticks
 * ({@code {position_ticks}}), so those three are all this needs.
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
