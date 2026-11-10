package com.packvium.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Compact JSON writer for the values {@link JsonReader} produces and the result maps hold. */
public final class JsonWriter {
    private JsonWriter() {
    }

    public static String write(Object value) {
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
            out.append(decimal.toString());
        } else if (value instanceof Double number) {
            appendDouble(out, number);
        } else if (value instanceof Map<?, ?> object) {
            appendObject(out, object);
        } else if (value instanceof List<?> array) {
            appendArray(out, array);
        } else {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> object) {
        out.append('{');
        Iterator<? extends Map.Entry<?, ?>> members = object.entrySet().iterator();
        while (members.hasNext()) {
            Map.Entry<?, ?> member = members.next();
            appendString(out, member.getKey().toString());
            out.append(':');
            append(out, member.getValue());
            if (members.hasNext()) {
                out.append(',');
            }
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

    private static void appendDouble(StringBuilder out, double number) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("JSON cannot carry " + number);
        }
        out.append(number);
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> appendCharacter(out, text, index, character);
            }
        }
        out.append('"');
    }

    /** Escapes control characters and lone surrogates, which UTF-8 output cannot carry. */
    private static void appendCharacter(StringBuilder out, String text, int index, char character) {
        boolean control = character < 0x20;
        boolean loneSurrogate = Character.isSurrogate(character) && !pairedSurrogate(text, index);
        if (control || loneSurrogate) {
            out.append(String.format("\\u%04x", (int) character));
        } else {
            out.append(character);
        }
    }

    private static boolean pairedSurrogate(String text, int index) {
        char character = text.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(text.charAt(index - 1));
    }
}
