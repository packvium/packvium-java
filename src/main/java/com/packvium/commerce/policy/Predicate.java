package com.packvium.commerce.policy;

import com.packvium.support.PhpArrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One condition over the caller-supplied context: context[field] &lt;op&gt; value (or, for
 * EXISTS/ABSENT, just whether field is present).
 *
 * <p>Values are the JSON values {@link com.packvium.serialization.JsonReader} yields. A
 * number that is not an exact 64-bit integer is a float to the reference port, and a float
 * never equals anything there, so it never equals anything here either.
 */
public record Predicate(PolicyScope scope, String field, PolicyOperator operator, Optional<Object> value) {
    public Predicate {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("field is required");
        }
        boolean unary = operator == PolicyOperator.EXISTS || operator == PolicyOperator.ABSENT;
        if (!unary && value.isEmpty()) {
            throw new IllegalArgumentException("operator '" + operator.value() + "' requires a value");
        }
    }

    public Predicate(PolicyScope scope, String field, PolicyOperator operator) {
        this(scope, field, operator, Optional.empty());
    }

    public boolean matches(Map<String, ?> context) {
        if (operator == PolicyOperator.EXISTS) {
            return context.containsKey(field);
        }
        if (operator == PolicyOperator.ABSENT) {
            return !context.containsKey(field);
        }
        if (!context.containsKey(field)) {
            return false;
        }
        Object actual = context.get(field);
        Object expected = value.orElse(null);
        return switch (operator) {
            case EQUALS -> equalValues(actual, expected);
            case NOT_EQUALS -> !equalValues(actual, expected);
            case IN -> contains(expected, actual);
            case NOT_IN -> !contains(expected, actual);
            default -> throw new UnsupportedPredicateException(
                    "unsupported policy operator '" + operator.value() + "'");
        };
    }

    /**
     * Value equality over the JSON scalar types, matching the reference implementation's
     * semantics exactly -- including that a boolean equals the integer it stands for,
     * which is the one place a naive equality would disagree and quietly change a decision.
     */
    private static boolean equalValues(Object left, Object right) {
        if (left instanceof Boolean || right instanceof Boolean) {
            if (left instanceof Boolean leftFlag && right instanceof Boolean rightFlag) {
                return leftFlag.equals(rightFlag);
            }
            return (left instanceof Long || right instanceof Long) && asInteger(left) == asInteger(right);
        }
        if (PhpArrays.isArray(left) && PhpArrays.isArray(right)) {
            return equalArrays(left, right);
        }
        if (left instanceof Long leftNumber && right instanceof Long rightNumber) {
            return leftNumber.equals(rightNumber);
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            return leftText.equals(rightText);
        }
        return left == null && right == null;
    }

    /** Same keys in the same order, and equal values under each key. O(n) per level. */
    private static boolean equalArrays(Object left, Object right) {
        if (PhpArrays.count(left) != PhpArrays.count(right)
                || !PhpArrays.keys(left).equals(PhpArrays.keys(right))) {
            return false;
        }
        // Identical key sequences mean the i-th value of each side sits under the same key.
        List<Object> leftValues = PhpArrays.values(left);
        List<Object> rightValues = PhpArrays.values(right);
        for (int index = 0; index < leftValues.size(); index++) {
            if (!equalValues(leftValues.get(index), rightValues.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Only reached with one Long and one Boolean, as PHP's (int) cast is there. */
    private static long asInteger(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return (Long) value;
    }

    private static boolean contains(Object haystack, Object needle) {
        if (haystack instanceof String text) {
            return needle instanceof String fragment && (fragment.isEmpty() || text.contains(fragment));
        }
        if (!PhpArrays.isArray(haystack)) {
            throw new UnsupportedPredicateException("an in/not_in predicate needs a list or string value");
        }
        for (Object candidate : PhpArrays.values(haystack)) {
            if (equalValues(candidate, needle)) {
                return true;
            }
        }
        return false;
    }
}
