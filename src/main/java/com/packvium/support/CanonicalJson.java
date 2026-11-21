package com.packvium.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 canonical JSON: the one spelling four engines can agree on byte for byte.
 *
 * <p>Held byte-identical to {@code packvium._canonical_json} and PHP's
 * {@code Support\CanonicalJson}, over the value trees {@link com.packvium.serialization.JsonReader}
 * yields and the {@code Map}/{@code List} trees this port builds:
 *
 * <ul>
 *   <li>Object keys are sorted by their UTF-16 code units, which is {@link String#compareTo}.
 *   <li>Strings escape {@code "}, {@code \} and U+0000..U+001F ({@code \b \t \n \f \r} by name,
 *       the rest as six characters with four lowercase hex digits); everything else, U+2028
 *       included, is written as itself.
 *   <li>Numbers are written as ECMAScript's {@code Number::toString} writes them, from the
 *       shortest round-trip digits ({@link ShortestFloat}); {@code -0} is {@code 0}.
 *   <li>No whitespace.
 * </ul>
 *
 * <p>A {@link BigDecimal} is a decoded non-integer JSON number, which every reference engine
 * holds as the nearest double, so it is written as that double: {@code 1.0} is {@code 1}.
 * An integer or a number whose magnitude exceeds 2^53 - 1, or that is not finite, is refused
 * ({@code number_out_of_range}); so is a string carrying a lone UTF-16 surrogate
 * ({@code invalid_string}), which has no UTF-8 spelling, and any value JSON has no type for,
 * a map key that is not a string included ({@code invalid_value}).
 *
 * <p>O(N + K log K) for N characters of output and K members in the largest object.
 */
public final class CanonicalJson {
    /** The largest magnitude every engine holds exactly. */
    public static final long MAX_EXACT_MAGNITUDE = 9_007_199_254_740_991L;

    private static final BigInteger MAX_EXACT = BigInteger.valueOf(MAX_EXACT_MAGNITUDE);
    private static final int MAX_FIXED_POINT = 21;
    private static final int MIN_FIXED_POINT = -6;

    private CanonicalJson() {
    }

    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /**
     * How a refusal quotes a value: its canonical JSON, the one spelling four engines share, so no
     * engine's own representation leaks into a message another engine must match.
     */
    public static String spelling(Object value) {
        try {
            return encode(value);
        } catch (CanonicalJsonException error) {
            return error.errorCode().equals("number_out_of_range") ? "an out-of-range number" : "an unspellable value";
        }
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof String text) {
            appendString(text, out);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            out.append(integer(((Number) value).longValue()));
        } else if (value instanceof BigInteger big) {
            out.append(integer(big));
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            out.append(number(((Number) value).doubleValue()));
        } else if (value instanceof Map<?, ?> object) {
            writeObject(object, out);
        } else if (value instanceof List<?> array) {
            writeArray(array, out);
        } else {
            throw new CanonicalJsonException("invalid_value", "a " + value.getClass().getName() + " has no JSON spelling");
        }
    }

    private static void writeArray(List<?> array, StringBuilder out) {
        out.append('[');
        for (int index = 0; index < array.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            write(array.get(index), out);
        }
        out.append(']');
    }

    private static void writeObject(Map<?, ?> object, StringBuilder out) {
        // Every name is checked and spelled before any value is written, as the reference does.
        List<Member> members = new ArrayList<>(object.size());
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new CanonicalJsonException("invalid_value", "object key " + entry.getKey() + " is not a string");
            }
            StringBuilder spelled = new StringBuilder(name.length() + 2);
            appendString(name, spelled);
            members.add(new Member(name, spelled.toString(), entry.getValue()));
        }
        members.sort((left, right) -> left.name().compareTo(right.name()));
        out.append('{');
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            Member member = members.get(index);
            out.append(member.spelled()).append(':');
            write(member.value(), out);
        }
        out.append('}');
    }

    private record Member(String name, String spelled, Object value) {
    }

    private static void appendString(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> index = appendCharacter(text, index, character, out);
            }
        }
        out.append('"');
    }

    /** Appends one character, or a whole surrogate pair, and returns the index of its last unit. */
    private static int appendCharacter(String text, int index, char character, StringBuilder out) {
        if (character < 0x20) {
            out.append("\\u00").append(Character.forDigit(character >> 4, 16)).append(Character.forDigit(character & 0xF, 16));
            return index;
        }
        if (!Character.isSurrogate(character)) {
            out.append(character);
            return index;
        }
        boolean paired = Character.isHighSurrogate(character) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1));
        if (!paired) {
            throw new CanonicalJsonException("invalid_string", "a string carries a lone UTF-16 surrogate");
        }
        out.append(character).append(text.charAt(index + 1));
        return index + 1;
    }

    private static String integer(long value) {
        if (value > MAX_EXACT_MAGNITUDE || value < -MAX_EXACT_MAGNITUDE) {
            throw new CanonicalJsonException("number_out_of_range", value + " is beyond what every engine holds exactly");
        }
        return Long.toString(value);
    }

    private static String integer(BigInteger value) {
        if (value.abs().compareTo(MAX_EXACT) > 0) {
            throw new CanonicalJsonException("number_out_of_range", value + " is beyond what every engine holds exactly");
        }
        return value.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_EXACT_MAGNITUDE) {
            throw new CanonicalJsonException("number_out_of_range",
                    PythonRepr.floating(value) + " is beyond what every engine holds exactly");
        }
        if (value == 0) {
            return "0";
        }
        ShortestFloat.Digits shortest = ShortestFloat.digits(Math.abs(value));
        String digits = shortest.digits();
        int count = digits.length();
        int point = shortest.point();
        String rendered;
        if (count <= point && point <= MAX_FIXED_POINT) {
            rendered = digits + "0".repeat(point - count);
        } else if (0 < point && point <= MAX_FIXED_POINT) {
            rendered = digits.substring(0, point) + "." + digits.substring(point);
        } else if (MIN_FIXED_POINT < point && point <= 0) {
            rendered = "0." + "0".repeat(-point) + digits;
        } else {
            int power = point - 1;
            rendered = digits.charAt(0) + (count > 1 ? "." + digits.substring(1) : "") + "e"
                    + (power >= 0 ? "+" : "-") + Math.abs(power);
        }
        return (value < 0 ? "-" : "") + rendered;
    }
}
