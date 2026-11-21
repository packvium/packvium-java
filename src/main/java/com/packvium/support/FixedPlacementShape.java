package com.packvium.support;

import com.packvium.validation.FixedPlacementException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The schema's shape of a request's {@code fixed_placements}, checked before anything is parsed.
 *
 * <p>Held to {@code packvium.fixed_placements.require_fixed_placement_shapes}. Nothing is coerced:
 * {@code "1"}, {@code true} and {@code 1.9} are not container instances, and a list is not a
 * position. The first entry out of shape is the refusal, {@code invalid_fixed_placement} with
 * reason {@code malformed} and the pointer of the bad value, spelled as every engine spells it.
 */
public final class FixedPlacementShape {
    public static final List<String> ORIENTATIONS = List.of("LWH", "LHW", "WLH", "WHL", "HLW", "HWL");
    private static final List<String> REQUIRED = List.of("item_type", "container_type", "orientation");
    private static final List<String> FIELDS = List.of("item_type", "container_type", "orientation",
            "container_instance", "position");
    private static final List<String> AXES = List.of("x", "y", "z");

    private FixedPlacementShape() {
    }

    /** The entries as JSON gave them; absent or null is none. O(f) for f entries. */
    public static List<Map<?, ?>> requireEntries(Object raw, String unit) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            throw malformed("fixed_placements is a list", "/fixed_placements");
        }
        List<Map<?, ?>> shaped = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            shaped.add(requireEntry(entries.get(index), "fixed_placements[" + index + "]", "/fixed_placements/" + index,
                    unit));
        }
        return shaped;
    }

    /**
     * A point is an object of {@code x}, {@code y} and {@code z} measures; what a measure is, is
     * the length parser's business, except the values no parser should be handed at all.
     * {@code refuse} builds the caller's own refusal from its message and the pointer of the bad
     * value.
     */
    public static Map<?, ?> requirePoint(Object point, String where, String field,
            BiFunction<String, String, RuntimeException> refuse) {
        if (!(point instanceof Map<?, ?> object)) {
            throw refuse.apply(where + " is a point object", field);
        }
        List<String> unknown = JsonValue.namesOutside(object, AXES);
        if (!unknown.isEmpty()) {
            throw refuse.apply(where + " does not carry " + CanonicalJson.spelling(unknown), field);
        }
        for (String axis : AXES) {
            Object value = object.get(axis);
            if (object.containsKey(axis) && (value == null || value instanceof Boolean || value instanceof List)) {
                throw refuse.apply(where + "." + axis + " is a measure", field + "/" + axis);
            }
        }
        return object;
    }

    private static Map<?, ?> requireEntry(Object raw, String where, String field, String unit) {
        if (!(raw instanceof Map<?, ?> entry)) {
            throw malformed(where + " is an object", field);
        }
        List<String> unknown = JsonValue.namesOutside(entry, FIELDS);
        if (!unknown.isEmpty()) {
            throw malformed(where + " does not carry " + CanonicalJson.spelling(unknown), field);
        }
        List<String> missing = REQUIRED.stream().filter(name -> !entry.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw malformed(where + " needs " + CanonicalJson.spelling(missing), field);
        }
        for (String name : List.of("item_type", "container_type")) {
            if (!(entry.get(name) instanceof String text) || text.isEmpty()) {
                throw malformed(where + "." + name + " is a non-empty string", field + "/" + name);
            }
        }
        if (!(entry.get("orientation") instanceof String orientation) || !ORIENTATIONS.contains(orientation)) {
            throw malformed(where + ".orientation is one of the six codes", field + "/orientation");
        }
        long instance = entry.containsKey("container_instance")
                ? JsonValue.integer(entry.get("container_instance")).orElse(0L)
                : 1;
        if (instance < 1) {
            throw malformed(where + ".container_instance counts from 1", field + "/container_instance");
        }
        Object position = entry.containsKey("position") ? entry.get("position") : Map.of();
        Map<?, ?> point = requirePoint(position, where + ".position", field + "/position",
                FixedPlacementShape::malformed);
        for (String axis : AXES) {
            if (point.containsKey(axis)) {
                requireCoordinate(point.get(axis), where + ".position." + axis, field + "/position/" + axis, unit);
            }
        }
        return entry;
    }

    /**
     * A coordinate is read in the request's length unit here, so a negative or unreadable one is
     * refused as a malformed placement rather than surfacing later as a bare parse error.
     */
    private static void requireCoordinate(Object value, String where, String field, String unit) {
        MeasureReading.Verdict verdict = MeasureReading.verdict(value, MeasureReading.Kind.LENGTH, unit);
        if (verdict == MeasureReading.Verdict.NEGATIVE) {
            throw malformed(where + " cannot be negative", field);
        }
        if (verdict == MeasureReading.Verdict.UNREADABLE) {
            throw malformed(where + " is a measure", field);
        }
    }

    private static FixedPlacementException malformed(String detail, String field) {
        return new FixedPlacementException(detail, "malformed", field);
    }
}
