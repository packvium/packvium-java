package com.packvium.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * A precomputed ordering key with the reference engine's tuple semantics.
 *
 * <p>Elements compare left to right; a key that is a prefix of another is smaller. Numbers
 * compare by exact value whatever their Java type, so a {@link BigInteger} volume and a long
 * area sit in one key without the chunking PHP needs. Strings compare by code point, as
 * Python does, not by UTF-16 unit. Booleans order false before true.
 */
public record SortKey(List<Object> elements) implements Comparable<SortKey> {
    public SortKey {
        elements = List.copyOf(elements);
    }

    public static SortKey of(Object... elements) {
        return new SortKey(List.of(elements));
    }

    @Override
    public int compareTo(SortKey other) {
        int shared = Math.min(elements.size(), other.elements.size());
        for (int index = 0; index < shared; index++) {
            int order = compareElements(elements.get(index), other.elements.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(elements.size(), other.elements.size());
    }

    private static int compareElements(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return compareNumbers(leftNumber, rightNumber);
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            return compareCodePoints(leftText, rightText);
        }
        if (left instanceof Boolean leftFlag && right instanceof Boolean rightFlag) {
            return Boolean.compare(leftFlag, rightFlag);
        }
        if (left instanceof SortKey leftKey && right instanceof SortKey rightKey) {
            return leftKey.compareTo(rightKey);
        }
        throw new IllegalArgumentException("Cannot order " + left + " against " + right);
    }

    /** Exact for finite values; an infinity (the reference's -INF sentinel) sorts past every finite one. */
    private static int compareNumbers(Number left, Number right) {
        int leftInfinity = infinitySign(left);
        int rightInfinity = infinitySign(right);
        if (leftInfinity != 0 || rightInfinity != 0) {
            return Integer.compare(leftInfinity, rightInfinity);
        }
        return exact(left).compareTo(exact(right));
    }

    private static int infinitySign(Number number) {
        if ((number instanceof Double || number instanceof Float) && Double.isNaN(number.doubleValue())) {
            throw new IllegalArgumentException("NaN has no order");
        }
        double value = number.doubleValue();
        if ((number instanceof Double || number instanceof Float) && Double.isInfinite(value)) {
            return value > 0 ? 1 : -1;
        }
        return 0;
    }

    private static BigDecimal exact(Number number) {
        if (number instanceof Long || number instanceof Integer) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof BigInteger whole) {
            return new BigDecimal(whole);
        }
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(number.doubleValue());
    }

    public static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }
}
