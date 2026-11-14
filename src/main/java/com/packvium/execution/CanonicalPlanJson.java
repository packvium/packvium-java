package com.packvium.execution;

import com.packvium.support.SortKey;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)},
 * reproduced byte for byte, because the reference plan is Python's string.
 *
 * <p>Neither {@link com.packvium.serialization.JsonWriter} nor the commerce encoder is reused:
 * Python spells backspace and form feed {@code \b}/{@code \f}, writes U+2028/U+2029 raw, and
 * encodes an empty object as {@code {}} and a {@code "0"}-keyed object as an object -- where
 * PHP's {@code json_encode}, which the commerce encoder follows, does neither. Keys sort by
 * code point, which is what {@code sort_keys} does with {@code str} keys. O(n log n) in the
 * document size, for the key sorts.
 */
final class CanonicalPlanJson {
    /** Python's {@code repr} switches to exponent notation outside this decimal-point window. */
    private static final int FIXED_MIN_DECIMAL_POINT = -3;
    private static final int FIXED_MAX_DECIMAL_POINT = 16;
    private static final int ROUND_TRIP_DIGITS = 17;

    private CanonicalPlanJson() {
    }

    static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer
                || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof BigDecimal decimal) {
            // Python decodes every non-integer JSON number to a float, and writes its repr.
            out.append(pythonFloat(decimal.doubleValue()));
        } else if (value instanceof Double number) {
            out.append(pythonFloat(number));
        } else if (value instanceof Map<?, ?> object) {
            appendObject(out, object);
        } else if (value instanceof List<?> array) {
            appendArray(out, array);
        } else {
            throw new ExecutionPlanException("Cannot serialize " + value.getClass().getName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> object) {
        List<String> names = new ArrayList<>(object.size());
        for (Object key : object.keySet()) {
            names.add(key.toString());
        }
        names.sort(SortKey::compareCodePoints);
        out.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String name = names.get(index);
            appendString(out, name);
            out.append(':');
            append(out, object.get(name));
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> array) {
        out.append('[');
        for (int index = 0; index < array.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            append(out, array.get(index));
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> appendCharacter(out, text, index, character);
            }
        }
        out.append('"');
    }

    private static void appendCharacter(StringBuilder out, String text, int index, char character) {
        if (character < 0x20) {
            out.append(String.format("\\u%04x", (int) character));
            return;
        }
        if (Character.isSurrogate(character) && !pairedSurrogate(text, index)) {
            // Python would hold the lone surrogate in a str, but no UTF-8 output can carry it,
            // so the plan could never be written down; refusing is the honest answer.
            throw new ExecutionPlanException("a lone surrogate cannot be encoded as UTF-8");
        }
        out.append(character);
    }

    private static boolean pairedSurrogate(String text, int index) {
        char character = text.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(text.charAt(index - 1));
    }

    /**
     * Python's {@code repr(float)}: the shortest decimal that reads back as the same double,
     * fixed notation for decimal points in [-3, 16] and exponent notation outside it.
     * Computed through {@link BigDecimal} so the digits never depend on the JDK's
     * {@code Double.toString}, whose shortest-digit guarantee arrived only in Java 19.
     */
    private static String pythonFloat(double value) {
        if (!Double.isFinite(value)) {
            throw new ExecutionPlanException("JSON cannot carry " + value);
        }
        if (value == 0) {
            return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
        }
        BigDecimal exact = new BigDecimal(value);
        BigDecimal shortest = exact.round(new MathContext(ROUND_TRIP_DIGITS, RoundingMode.HALF_EVEN));
        for (int precision = 1; precision < ROUND_TRIP_DIGITS; precision++) {
            BigDecimal rounded = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (rounded.doubleValue() == value) {
                shortest = rounded;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().abs().toString();
        int decimalPoint = digits.length() - shortest.scale();
        String sign = value < 0 ? "-" : "";
        if (decimalPoint < FIXED_MIN_DECIMAL_POINT || decimalPoint > FIXED_MAX_DECIMAL_POINT) {
            int exponent = decimalPoint - 1;
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            String magnitude = Integer.toString(Math.abs(exponent));
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + (magnitude.length() == 1 ? "0" : "")
                    + magnitude;
        }
        if (decimalPoint <= 0) {
            return sign + "0." + "0".repeat(-decimalPoint) + digits;
        }
        if (decimalPoint >= digits.length()) {
            return sign + digits + "0".repeat(decimalPoint - digits.length()) + ".0";
        }
        return sign + digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint);
    }
}
