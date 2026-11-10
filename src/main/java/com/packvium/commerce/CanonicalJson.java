package com.packvium.commerce;

import com.packvium.support.PhpArrays;
import com.packvium.support.SortKey;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PHP's {@code json_encode} of a key-sorted result, with {@code JSON_UNESCAPED_SLASHES |
 * JSON_UNESCAPED_UNICODE}, reproduced byte for byte. {@link com.packvium.serialization.JsonWriter}
 * is not reused because PHP spells backspace and form feed as {@code \b}/{@code \f} and
 * still escapes U+2028/U+2029 under that flag set, and its empty object is {@code []}.
 * O(n log n) in the document size, for the key sorts.
 */
final class CanonicalJson {
    private CanonicalJson() {
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
        } else if (value instanceof Map<?, ?> object) {
            appendObject(out, object);
        } else if (value instanceof List<?> array) {
            appendArray(out, array);
        } else {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getName());
        }
    }

    /** ksort(SORT_STRING), after which a 0..n-1 keyed object is a list to PHP. */
    private static void appendObject(StringBuilder out, Map<?, ?> object) {
        List<String> names = new ArrayList<>();
        for (Object key : object.keySet()) {
            names.add(key.toString());
        }
        names.sort(SortKey::compareCodePoints);
        List<Object> sortedValues = new ArrayList<>(names.size());
        for (String name : names) {
            sortedValues.add(object.get(name));
        }
        if (isIndexSequence(names)) {
            appendArray(out, sortedValues);
            return;
        }
        out.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            appendString(out, names.get(index));
            out.append(':');
            append(out, sortedValues.get(index));
        }
        out.append('}');
    }

    /** PHP's array_is_list over sorted keys; true for no keys, so {} encodes as []. */
    private static boolean isIndexSequence(List<String> names) {
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (!PhpArrays.isIntegerKey(name) || Long.parseLong(name) != index) {
                return false;
            }
        }
        return true;
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
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
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
            // PHP refuses to encode a string that is not valid UTF-8 rather than mangle it.
            throw new IllegalStateException("Malformed UTF-8 characters, possibly incorrectly encoded");
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
}
