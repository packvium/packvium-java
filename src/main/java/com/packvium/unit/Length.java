package com.packvium.unit;

import com.packvium.support.RationalParser;
import com.packvium.support.Text;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact fixed-point length. One tick equals 1/16000 mm. */
public record Length(long ticks) implements Comparable<Length> {
    public static final long TICKS_PER_MM = 16_000;
    public static final long TICKS_PER_INCH = 406_400;
    public static final Length ZERO = new Length(0);

    private static final MeasureParser PARSER = new MeasureParser(
            Pattern.compile("^(.+?)\\s*(mm|cm|m|in|inch|inches|ft|ticks?)$", Pattern.CASE_INSENSITIVE),
            Length::multiplier,
            "length");

    public Length {
        if (ticks < 0) {
            throw new IllegalArgumentException("Length cannot be negative");
        }
    }

    public static Length of(String value, String unit, Rounding rounding) {
        return new Length(PARSER.scaled(value, unit, rounding));
    }

    public static Length of(long value, String unit, Rounding rounding) {
        return new Length(RationalParser.scaled(value, multiplier(unit), rounding));
    }

    public static Length mm(String value) {
        return of(value, "mm", Rounding.NEAREST);
    }

    public static Length mm(long value) {
        return of(value, "mm", Rounding.NEAREST);
    }

    public static Length inches(String value) {
        return of(value, "in", Rounding.NEAREST);
    }

    /** Reads any request spelling of a length: integer, string with optional unit, or {value, unit}. */
    public static Length parse(Object value, String defaultUnit, Rounding rounding) {
        if (value instanceof Length length) {
            return length;
        }
        return new Length(PARSER.ticks(value, defaultUnit, rounding));
    }

    public static Length parse(Object value, String defaultUnit) {
        return parse(value, defaultUnit, Rounding.NEAREST);
    }

    /** A bare number is millimetres, as in PHP's {@code Length::parse}. */
    public static Length parse(Object value) {
        return parse(value, "mm");
    }

    public static long multiplier(String unit) {
        return switch (Text.unitKey(unit)) {
            case "tick", "ticks" -> 1;
            case "mm", "millimeter", "millimeters" -> TICKS_PER_MM;
            case "cm" -> TICKS_PER_MM * 10;
            case "m" -> TICKS_PER_MM * 1000;
            case "in", "inch", "inches" -> TICKS_PER_INCH;
            case "ft" -> TICKS_PER_INCH * 12;
            default -> throw new IllegalArgumentException("Unsupported length unit: " + unit);
        };
    }

    public Length plus(Length other) {
        return new Length(Math.addExact(ticks, other.ticks));
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
    public int compareTo(Length other) {
        return Long.compare(ticks, other.ticks);
    }
}
