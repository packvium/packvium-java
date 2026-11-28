package com.packvium.validation;

import com.packvium.support.PythonRepr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Python's {@code str()} and {@code repr()} for the values an issue detail prints.
 *
 * <p>The reference builds every detail with an f-string, so a detail carries Python's spelling of
 * whatever it names -- {@code None}, {@code True}, {@code 'quoted'}, {@code (1, 2, 3)},
 * {@code 1e-05} -- and matching it is what lets one issue list be compared across languages
 * character for character. Which characters {@code repr} escapes comes from {@link NonPrintable},
 * never from this JDK's Unicode tables.
 */
final class PyFormat {
    private PyFormat() {
    }

    /** {@code str(value)}: a string as itself, anything else as its {@code repr}. */
    static String str(Object value) {
        return value instanceof String text ? text : repr(value);
    }

    static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        BigInteger whole = Json.strictInt(value);
        if (whole != null) {
            return whole.toString();
        }
        if (value instanceof BigDecimal) {
            return PythonRepr.floating(Json.floating(value));
        }
        if (value instanceof String text) {
            return string(text);
        }
        if (value instanceof List<?> list) {
            List<String> elements = new ArrayList<>(list.size());
            list.forEach(element -> elements.add(repr(element)));
            return listOf(elements);
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            pairs.add(string(String.valueOf(entry.getKey())) + ": " + repr(entry.getValue()));
        }
        return "{" + String.join(", ", pairs) + "}";
    }

    /** {@code [a, b]} from already-formatted elements. */
    static String listOf(List<String> elements) {
        return "[" + String.join(", ", elements) + "]";
    }

    /** {@code (a, b)}; a one-element tuple keeps its trailing comma. */
    static String tupleOf(List<String> elements) {
        return elements.size() == 1 ? "(" + elements.get(0) + ",)" : "(" + String.join(", ", elements) + ")";
    }

    static String intTuple(List<BigInteger> values) {
        return tupleOf(values.stream().map(BigInteger::toString).toList());
    }

    static String intList(List<BigInteger> values) {
        return listOf(values.stream().map(BigInteger::toString).toList());
    }

    static String valueTuple(List<?> values) {
        List<String> elements = new ArrayList<>(values.size());
        values.forEach(value -> elements.add(repr(value)));
        return tupleOf(elements);
    }

    static String boolRepr(boolean flag) {
        return flag ? "True" : "False";
    }

    /** {@code f"{value:.6f}"}: the exact binary value rounded half to even, as Python rounds it. */
    static String fixed6(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String digits = new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        boolean negativeZero = value == 0 && 1 / value < 0;
        return negativeZero || (value < 0 && !digits.startsWith("-")) ? "-" + digits : digits;
    }

    /**
     * {@code repr(str)}: single quotes unless the text holds a single quote and no double quote,
     * Python's escapes for the backslash, the quote, {@code \t}, {@code \n}, {@code \r} and every
     * other non-printable code point, by width.
     */
    static String string(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        text.codePoints().forEach(code -> appendEscaped(out, code, quote));
        return out.append(quote).toString();
    }

    private static void appendEscaped(StringBuilder out, int code, char quote) {
        if (code == '\\') {
            out.append("\\\\");
        } else if (code == '\t') {
            out.append("\\t");
        } else if (code == '\n') {
            out.append("\\n");
        } else if (code == '\r') {
            out.append("\\r");
        } else if (code == quote) {
            out.append('\\').append(quote);
        } else if (code < 0x20 || code == 0x7f) {
            out.append(String.format(Locale.ROOT, "\\x%02x", code));
        } else if (code < 0x7f || !NonPrintable.contains(code)) {
            out.appendCodePoint(code);
        } else if (code <= 0xff) {
            out.append(String.format(Locale.ROOT, "\\x%02x", code));
        } else if (code <= 0xffff) {
            out.append(String.format(Locale.ROOT, "\\u%04x", code));
        } else {
            out.append(String.format(Locale.ROOT, "\\U%08x", code));
        }
    }
}
