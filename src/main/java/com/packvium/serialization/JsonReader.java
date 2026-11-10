package com.packvium.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 reader into immutable Java values.
 *
 * <p>Integers stay exact ({@link Long}, or {@link BigInteger} past its range); any other number
 * is a {@link BigDecimal}, never a double, so a measure can refuse it instead of rounding it.
 * Objects keep key order, and a repeated key keeps its first position with its last value,
 * as the reference engines' decoders do. O(n) in the input length.
 */
public final class JsonReader {
    private static final int MAX_DEPTH = 512;
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final String text;
    private int position;

    private JsonReader(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.position != text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("maximum nesting depth exceeded");
        }
        if (position >= text.length()) {
            throw error("unexpected end of input");
        }
        char next = text.charAt(position);
        return switch (next) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        position++;
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return Collections.unmodifiableMap(members);
        }
        do {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '"') {
                throw error("expected a member name");
            }
            String name = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(name, value(depth + 1));
            skipWhitespace();
        } while (consume(','));
        expect('}');
        return Collections.unmodifiableMap(members);
    }

    private List<Object> array(int depth) {
        position++;
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return Collections.unmodifiableList(elements);
        }
        do {
            skipWhitespace();
            elements.add(value(depth + 1));
            skipWhitespace();
        } while (consume(','));
        expect(']');
        return Collections.unmodifiableList(elements);
    }

    private String string() {
        position++;
        StringBuilder decoded = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            char character = text.charAt(position++);
            if (character == '"') {
                return decoded.toString();
            }
            if (character < 0x20) {
                throw error("control character in string");
            }
            decoded.append(character == '\\' ? escape() : character);
        }
    }

    private char escape() {
        if (position >= text.length()) {
            throw error("unterminated escape");
        }
        char code = text.charAt(position++);
        return switch (code) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicodeEscape();
            default -> throw error("invalid escape");
        };
    }

    private char unicodeEscape() {
        if (position + 4 > text.length()) {
            throw error("truncated unicode escape");
        }
        String hex = text.substring(position, position + 4);
        if (!hex.matches("[0-9a-fA-F]{4}")) {
            throw error("invalid unicode escape");
        }
        position += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Object number() {
        int start = position;
        consume('-');
        if (!consume('0')) {
            requireDigits();
        }
        boolean integral = true;
        if (consume('.')) {
            integral = false;
            requireDigits();
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            integral = false;
            if (!consume('+')) {
                consume('-');
            }
            requireDigits();
        }
        String literal = text.substring(start, position);
        if (!integral) {
            return new BigDecimal(literal);
        }
        BigInteger value = new BigInteger(literal);
        boolean fitsLong = value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
        return fitsLong ? (Object) value.longValue() : value;
    }

    private void requireDigits() {
        int start = position;
        while (position < text.length() && Character.isDigit(text.charAt(position)) && text.charAt(position) < 128) {
            position++;
        }
        if (position == start) {
            throw error("expected a digit");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("invalid literal");
        }
        position += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character != ' ' && character != '\t' && character != '\n' && character != '\r') {
                return;
            }
            position++;
        }
    }

    private boolean consume(char expected) {
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String reason) {
        return new IllegalArgumentException("Syntax error: " + reason + " at offset " + position);
    }
}
