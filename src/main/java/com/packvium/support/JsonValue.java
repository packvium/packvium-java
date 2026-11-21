package com.packvium.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Judgements over a decoded JSON tree ({@code Map}, {@code List}, {@code String}, {@code Boolean},
 * {@code Long}, {@code BigInteger}, {@code BigDecimal}, null) that four engines must make alike.
 */
public final class JsonValue {
    private static final BigInteger MAX_EXACT = BigInteger.valueOf(CanonicalJson.MAX_EXACT_MAGNITUDE);

    private JsonValue() {
    }

    /**
     * The integer a JSON number is, judged by value as every engine can judge it: {@code 1.0} is
     * 1, because JavaScript cannot tell them apart once the text is parsed; a boolean, a string,
     * {@code 1.5} and anything past 2^53 - 1 -- which JavaScript no longer holds exactly -- are
     * not integers. The bound also means {@code + 1} on a returned value can never overflow.
     */
    public static Optional<Long> integer(Object value) {
        BigInteger whole = wholeNumber(value);
        if (whole == null || whole.abs().compareTo(MAX_EXACT) > 0) {
            return Optional.empty();
        }
        return Optional.of(whole.longValue());
    }

    /** The whole number a JSON number is, of any finite magnitude, or null for anything else. */
    public static BigInteger wholeNumber(Object value) {
        if (value instanceof Long || value instanceof Integer) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger big) {
            return big;
        }
        // A fraction is judged as the double every reference engine decodes it to: `1e400` is
        // infinite there, and `1.0000000000000001` is 1.
        double number;
        if (value instanceof BigDecimal decimal) {
            number = decimal.doubleValue();
        } else if (value instanceof Double floating) {
            number = floating;
        } else {
            return null;
        }
        if (!Double.isFinite(number) || Math.floor(number) != number) {
            return null;
        }
        return new BigDecimal(number).toBigInteger();
    }

    /** A JSON number: any integer or fraction, never a boolean. */
    public static boolean isNumber(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof Double;
    }

    /**
     * An object's member names that are not in {@code allowed}, sorted by code point, so every
     * engine lists them alike. O(m log m) for m members.
     */
    public static List<String> namesOutside(Map<?, ?> object, Collection<String> allowed) {
        List<String> unknown = new ArrayList<>();
        for (Object name : object.keySet()) {
            if (!allowed.contains(name)) {
                unknown.add(name.toString());
            }
        }
        unknown.sort(SortKey::compareCodePoints);
        return unknown;
    }
}
