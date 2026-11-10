package com.packvium.unit;

import com.packvium.support.RationalParser;
import com.packvium.support.Text;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact weight. One tick equals 1/8 microgram. */
public record Weight(long ticks) implements Comparable<Weight> {
    public static final long TICKS_PER_MG = 8_000;
    public static final long TICKS_PER_G = 8_000_000;
    public static final long TICKS_PER_KG = 8_000_000_000L;
    public static final long TICKS_PER_OZ = 226_796_185;
    public static final long TICKS_PER_LB = 3_628_738_960L;
    public static final Weight ZERO = new Weight(0);

    private static final MeasureParser PARSER = new MeasureParser(
            Pattern.compile("^(.+?)\\s*(mg|kg|g|oz|lbs?|ticks?)$", Pattern.CASE_INSENSITIVE),
            Weight::multiplier,
            "weight");

    public Weight {
        if (ticks < 0) {
            throw new IllegalArgumentException("Weight cannot be negative");
        }
    }

    public static Weight of(String value, String unit, Rounding rounding) {
        return new Weight(PARSER.scaled(value, unit, rounding));
    }

    public static Weight of(long value, String unit, Rounding rounding) {
        return new Weight(RationalParser.scaled(value, multiplier(unit), rounding));
    }

    /** Reads any request spelling of a weight: integer, string with optional unit, or {value, unit}. */
    public static Weight parse(Object value, String defaultUnit, Rounding rounding) {
        if (value instanceof Weight weight) {
            return weight;
        }
        return new Weight(PARSER.ticks(value, defaultUnit, rounding));
    }

    public static Weight parse(Object value, String defaultUnit) {
        return parse(value, defaultUnit, Rounding.NEAREST);
    }

    /** A bare number is grams, as in PHP's {@code Weight::parse}. */
    public static Weight parse(Object value) {
        return parse(value, "g");
    }

    public static long multiplier(String unit) {
        return switch (Text.unitKey(unit)) {
            case "tick", "ticks" -> 1;
            case "mg" -> TICKS_PER_MG;
            case "g" -> TICKS_PER_G;
            case "kg" -> TICKS_PER_KG;
            case "oz" -> TICKS_PER_OZ;
            case "lb", "lbs" -> TICKS_PER_LB;
            default -> throw new IllegalArgumentException("Unsupported weight unit: " + unit);
        };
    }

    public Weight plus(Weight other) {
        return new Weight(Math.addExact(ticks, other.ticks));
    }

    /** Exact decimal string in {@code unit}, rounded to eight places, ties to even. */
    public String decimal(String unit) {
        return RationalParser.decimalString(ticks, multiplier(unit), 8);
    }

    public Map<String, Object> toMap(String unit) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("ticks", ticks);
        rendered.put("value", decimal(unit));
        rendered.put("unit", unit);
        return rendered;
    }

    @Override
    public int compareTo(Weight other) {
        return Long.compare(ticks, other.ticks);
    }
}
