package com.packvium.support;

import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a decoded JSON value reads as a length or weight, judged the way {@code Length.parse}
 * and {@code Weight.parse} judge it in the Python engine.
 *
 * <p>The value is normalised first to what the parsers take -- an integer, a string, or an
 * object of {@code value} and an optional string {@code unit} -- so a refusal is always a verdict.
 * O(length of the value's text).
 */
public final class MeasureReading {
    /** The two quantities a request measures. */
    public enum Kind {
        LENGTH, WEIGHT;

        Object parse(Object value, String unit) {
            return this == LENGTH ? Length.parse(value, unit) : Weight.parse(value, unit);
        }

        boolean knows(String unit) {
            try {
                return (this == LENGTH ? Length.multiplier(unit) : Weight.multiplier(unit)) > 0;
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }
    }

    /** What reading a value as a measure gives. */
    public enum Verdict {
        READABLE, NEGATIVE, UNREADABLE
    }

    private MeasureReading() {
    }

    public static Verdict verdict(Object value, Kind kind, String unit) {
        Optional<Object> normalised = normalised(value);
        if (normalised.isEmpty()) {
            return Verdict.UNREADABLE;
        }
        try {
            kind.parse(normalised.get(), unit);
        } catch (IllegalArgumentException | ArithmeticException error) {
            String message = error.getMessage();
            return message != null && message.contains("cannot be negative") ? Verdict.NEGATIVE : Verdict.UNREADABLE;
        }
        return Verdict.READABLE;
    }

    /**
     * The ticks of a value {@link #verdict} already found readable, for a rule that compares
     * measures.
     */
    public static long ticks(Object value, Kind kind, String unit) {
        Object parsed = kind.parse(normalised(value).orElseThrow(), unit);
        return parsed instanceof Length length ? length.ticks() : ((Weight) parsed).ticks();
    }

    public static boolean knowsUnit(Kind kind, String unit) {
        return kind.knows(unit);
    }

    /** What the parser can be handed, or empty when the Python engine could not read it either. */
    private static Optional<Object> normalised(Object value) {
        if (value instanceof Long || value instanceof String) {
            return Optional.of(value);
        }
        if (value instanceof BigInteger) {
            return Optional.of(value);
        }
        if (value instanceof BigDecimal) {
            // An integral float is the integer JavaScript sends for `100`; a fraction in binary
            // floating point is not the decimal the caller wrote. A negative whole one is read, so
            // it is refused for its sign whatever its magnitude, as the reference refuses it.
            BigInteger whole = JsonValue.wholeNumber(value);
            if (whole != null && whole.signum() < 0) {
                return Optional.of(whole);
            }
            return JsonValue.integer(value).map(Object.class::cast);
        }
        if (!(value instanceof Map<?, ?> object) || !object.containsKey("value")) {
            return Optional.empty();
        }
        Optional<Object> amount = amount(object.get("value"));
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> measure = new LinkedHashMap<>();
        measure.put("value", amount.get());
        if (object.containsKey("unit")) {
            if (!(object.get("unit") instanceof String stated)) {
                return Optional.empty();
            }
            measure.put("unit", stated);
        }
        return Optional.of(measure);
    }

    /**
     * An object measure's {@code value}, which the Python engine reads as a number or its text: a
     * boolean is the integer it is in Python, and a float only when it is whole.
     */
    private static Optional<Object> amount(Object amount) {
        if (amount instanceof Long || amount instanceof String || amount instanceof BigInteger) {
            return Optional.of(amount);
        }
        // A boolean is a number to some JSON readers, but never a measure anyone wrote: unreadable.
        if (amount instanceof BigDecimal) {
            // A fraction in binary floating point is refused here as it is bare.
            return Optional.ofNullable(JsonValue.wholeNumber(amount));
        }
        return Optional.empty();
    }
}
