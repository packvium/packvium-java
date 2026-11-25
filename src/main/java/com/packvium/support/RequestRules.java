package com.packvium.support;

import com.packvium.domain.FlatBag;
import com.packvium.domain.MadeToMeasure;
import com.packvium.domain.Sides;
import com.packvium.serialization.InvalidRequestException;
import com.packvium.support.MeasureReading.Kind;
import com.packvium.support.MeasureReading.Verdict;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The request schema's rules, walked over the decoded JSON before any model is built.
 *
 * <p>The first violation wins, in the order every engine follows -- units, configuration, items,
 * containers -- and is refused with the same reason, JSON Pointer and message the Python engine
 * raises. O(size of the request), plus
 * O(t log t) to walk an object of t tag limits in code-point order.
 */
public final class RequestRules {
    public static final List<String> SOLVER_PROFILES = List.of("fast", "balanced", "quality", "exact_small");
    public static final List<String> OBJECTIVES = List.of("default", "lowest_cost", "shipping_cost",
            "lowest_landed_cost", "open_dimension_height", "maximum_value", "maximum_volume");
    public static final List<String> ACCESS_DIRECTIONS = List.of("+x", "-x", "+y", "-y", "+z", "-z");

    private static final List<Map.Entry<String, Long>> CONFIGURATION_INTEGERS = List.of(
            Map.entry("time_limit_ms", 1L), Map.entry("alternatives", 1L), Map.entry("max_containers", 1L),
            Map.entry("exact_item_limit", 1L), Map.entry("multi_start_orders", 1L),
            Map.entry("max_candidates_per_item", 1L), Map.entry("max_candidate_points", 16L),
            Map.entry("container_plan_beam_width", 1L), Map.entry("container_plan_node_limit", 1L),
            Map.entry("dimensional_weight_divisor", 1L));
    private static final List<String> EFFORT_LIMITS = List.of("max_candidates_evaluated", "max_placement_attempts",
            "max_search_nodes", "max_restarts");
    /** Every key the request schema's {@code configuration} declares; it sets {@code additionalProperties: false}. */
    private static final Set<String> CONFIGURATION_FIELDS = Set.of(
            "alternatives", "clearance", "container_plan_beam_width", "container_plan_node_limit",
            "dimensional_weight_divisor", "dimensional_weight_length_unit", "dimensional_weight_weight_unit",
            "effort_budget", "exact_item_limit", "max_candidate_points", "max_candidates_per_item", "max_containers",
            "minimum_support_ratio", "multi_start_orders", "objective", "require_placement_coordinates", "seed",
            "solver_profile", "solvers", "time_limit_ms");
    private static final List<String> SIDES = List.of("length", "width", "height");
    private static final List<String> AXES = List.of("x", "y", "z");
    private static final String WEIGHT_UNIT = "g";
    /** Container fields a flat bag refuses, in reporting order. */
    private static final List<String> BAG_EXCLUSIONS = List.of("obstacles", "axles", "access_directions",
            "max_stack_density", "outer_dimensions", "void_fill_reserve_ratio", "made_to_measure");
    /** Every key the request schema's {@code made_to_measure} declares; it sets {@code additionalProperties: false}. */
    private static final Set<String> MADE_TO_MEASURE_FIELDS = Set.of("cost_per_square_metre_minor", "increment",
            "minimum", "outer_allowance", "tare_per_square_metre");

    private RequestRules() {
    }

    /** Refuse the request with its first violation, or return. */
    public static void check(Object data) {
        Map<?, ?> request = objectAt(data, "");
        String unit = units(request.get("units"));
        configuration(request.get("configuration"), unit);
        List<?> items = requiredList(request, "items", "");
        for (int index = 0; index < items.size(); index++) {
            item(items.get(index), "/items/" + index, unit);
        }
        requireUniqueIds(items, "items");
        List<?> containers = requiredList(request, "containers", "");
        for (int index = 0; index < containers.size(); index++) {
            container(containers.get(index), "/containers/" + index, unit);
        }
        requireUniqueIds(containers, "containers");
    }

    /** The RFC 6901 pointer to one member, escaping {@code ~} and {@code /} in its name. */
    public static String pointer(String base, String name) {
        return base + "/" + name.replace("~", "~0").replace("/", "~1");
    }

    // ---------------------------------------------------------------------------- the rules

    private static String units(Object raw) {
        if (raw == null) {
            return "mm";
        }
        Object length = objectAt(raw, "/units").get("length");
        if (length == null) {
            return "mm";
        }
        if (!(length instanceof String stated) || !MeasureReading.knowsUnit(Kind.LENGTH, stated)) {
            throw refuse("invalid_unit", "/units/length", "has an unknown unit " + CanonicalJson.spelling(length));
        }
        return stated;
    }

    private static void configuration(Object raw, String unit) {
        if (raw == null) {
            return;
        }
        String where = "/configuration";
        Map<?, ?> configuration = objectAt(raw, where);
        knownFields(configuration, where, CONFIGURATION_FIELDS);
        optionalOneOf(configuration, "solver_profile", where, SOLVER_PROFILES);
        optionalOneOf(configuration, "objective", where, OBJECTIVES);
        for (Map.Entry<String, Long> rule : CONFIGURATION_INTEGERS) {
            optionalInteger(configuration, rule.getKey(), where, rule.getValue());
        }
        optionalRatio(configuration, "minimum_support_ratio", where, 1L);
        optionalMeasure(configuration, "clearance", where, Kind.LENGTH, unit);
        Object effort = configuration.get("effort_budget");
        if (effort != null) {
            Map<?, ?> budget = objectAt(effort, where + "/effort_budget");
            knownFields(budget, where + "/effort_budget", EFFORT_LIMITS);
            for (String name : EFFORT_LIMITS) {
                optionalInteger(budget, name, where + "/effort_budget", 1);
            }
        }
    }

    private static void item(Object raw, String where, String unit) {
        Map<?, ?> item = objectAt(raw, where);
        requiredString(item, "id", where);
        optionalInteger(item, "quantity", where, 1);
        dimensions(required(item, "dimensions", where), where + "/dimensions", unit);
        optionalMeasure(item, "weight", where, Kind.WEIGHT, WEIGHT_UNIT);
        optionalMeasure(item, "max_top_load", where, Kind.WEIGHT, WEIGHT_UNIT);
        optionalMeasure(item, "nesting_height", where, Kind.LENGTH, unit);
        optionalInteger(item, "max_stacked_items", where, 1);
        optionalInteger(item, "stop_index", where, 0);
        optionalInteger(item, "value", where, 0);
        optionalInteger(item, "max_compression_pressure_kpa", where, 0);
        optionalRatio(item, "minimum_support_ratio", where, 1L);
        optionalRatio(item, "compression_ratio", where, null);
        Object stackingGroup = item.get("stacking_group");
        if (stackingGroup != null) {
            stackingGroupName(stackingGroup, where + "/stacking_group");
        }
        Object accepts = item.get("accepts_stacking_groups");
        if (accepts != null) {
            List<?> names = listAt(accepts, where + "/accepts_stacking_groups");
            for (int index = 0; index < names.size(); index++) {
                stackingGroupName(names.get(index), where + "/accepts_stacking_groups/" + index);
            }
        }
    }

    private static void stackingGroupName(Object value, String field) {
        if (!(value instanceof String name)) {
            throw refuse("wrong_type", field, "must be a string");
        }
        if (name.isEmpty()) {
            throw refuse("invalid_value", field, "must not be empty");
        }
    }

    private static void container(Object raw, String where, String unit) {
        Map<?, ?> container = objectAt(raw, where);
        requiredString(container, "id", where);
        optionalInteger(container, "quantity", where, 1);
        dimensions(required(container, "inner_dimensions", where), where + "/inner_dimensions", unit);
        Object outer = container.get("outer_dimensions");
        if (outer != null) {
            dimensions(outer, where + "/outer_dimensions", unit);
        }
        for (String name : List.of("tare_weight", "max_payload", "max_stack_density")) {
            optionalMeasure(container, name, where, Kind.WEIGHT, WEIGHT_UNIT);
        }
        optionalInteger(container, "max_items", where, 1);
        optionalInteger(container, "cost_minor", where, 0);
        optionalRatio(container, "void_fill_reserve_ratio", where, 1L);
        Object accessDirections = container.get("access_directions");
        if (accessDirections != null) {
            accessDirections(accessDirections, where + "/access_directions");
        }
        Object tagLimits = container.get("tag_limits");
        if (tagLimits != null) {
            tagLimits(tagLimits, where + "/tag_limits");
        }
        Object rateTable = container.get("rate_table");
        if (rateTable != null) {
            rateTable(rateTable, where + "/rate_table");
        }
        Object obstacles = container.get("obstacles");
        if (obstacles != null) {
            obstacles(obstacles, where + "/obstacles", unit);
        }
        Object boundary = container.get("deformable_boundary");
        if (boundary != null) {
            deformableBoundary(container, boundary, where);
        }
        Object machine = container.get("made_to_measure");
        if (machine != null) {
            madeToMeasure(container, machine, where, unit);
        }
    }

    /**: the bag's own shape, then every container field a flat bag refuses, in order. */
    private static void deformableBoundary(Map<?, ?> container, Object raw, String where) {
        String field = where + "/deformable_boundary";
        Map<?, ?> boundary = objectAt(raw, field);
        oneOf(required(boundary, "kind", field), field + "/kind", FlatBag.KINDS);
        knownFields(boundary, field, List.of("kind"));
        for (String name : BAG_EXCLUSIONS) {
            if (setBesideABag(name, container.get(name))) {
                throw refuse("not_allowed", where + "/" + name, "cannot be combined with deformable_boundary");
            }
        }
    }

    /** An empty obstacle list and a zero reserve change nothing, so only they are let through. */
    private static boolean setBesideABag(String name, Object value) {
        if (name.equals("obstacles")) {
            // Already a list here: the obstacle rules ran first.
            return value instanceof List<?> list && !list.isEmpty();
        }
        if (name.equals("void_fill_reserve_ratio")) {
            return value != null && compare(value, 0) > 0;
        }
        return value != null;
    }

    /**
     * The made-to-measure block's shape top to bottom, then each axis's four rules, then
     * the price ceiling. Run after every other container field, so an existing error always wins.
     * O(1); the price is exact, so no product can wrap.
     */
    private static void madeToMeasure(Map<?, ?> container, Object raw, String where, String unit) {
        String field = where + "/made_to_measure";
        Map<?, ?> machine = objectAt(raw, field);
        knownFields(machine, field, MADE_TO_MEASURE_FIELDS);
        for (String name : List.of("minimum", "increment")) {
            required(machine, name, field);
        }
        for (String name : List.of("minimum", "increment", "outer_allowance")) {
            Object sides = machine.get(name);
            if (sides != null) {
                dimensions(sides, field + "/" + name, unit);
            }
        }
        optionalInteger(machine, "cost_per_square_metre_minor", field, 0);
        optionalMeasure(machine, "tare_per_square_metre", field, Kind.WEIGHT, WEIGHT_UNIT);
        if (container.get("outer_dimensions") != null) {
            throw refuse("not_allowed", where + "/outer_dimensions", "cannot be combined with made_to_measure");
        }
        long[] inner = sideTicks(container.get("inner_dimensions"), unit);
        long[] minimum = sideTicks(machine.get("minimum"), unit);
        long[] increment = sideTicks(machine.get("increment"), unit);
        for (int axis = 0; axis < 3; axis++) {
            String side = SIDES.get(axis);
            if (minimum[axis] == 0) {
                throw refuse("invalid_value", field + "/minimum/" + side, "must be positive");
            }
            if (increment[axis] == 0) {
                throw refuse("invalid_value", field + "/increment/" + side, "must be positive");
            }
            if (minimum[axis] > inner[axis]) {
                throw refuse("invalid_value", field + "/minimum/" + side, "exceeds inner_dimensions");
            }
            if ((inner[axis] - minimum[axis]) % increment[axis] != 0) {
                throw refuse("invalid_value", field + "/increment/" + side,
                        "does not divide inner_dimensions minus minimum");
            }
        }
        long price = wholeOrZero(machine.get("cost_per_square_metre_minor"));
        long fee = wholeOrZero(container.get("cost_minor"));
        if (MadeToMeasure.boxCostFor(price, fee, new Sides(inner[0], inner[1], inner[2]))
                .compareTo(MadeToMeasure.MAX_EXACT_INTEGER) > 0) {
            throw refuse("invalid_value", field + "/cost_per_square_metre_minor",
                    "prices the largest box above " + CanonicalJson.MAX_EXACT_MAGNITUDE);
        }
    }

    /** An integer the rules above already admitted, or 0 for an absent or falsy one. */
    private static long wholeOrZero(Object value) {
        return value == null ? 0 : JsonValue.integer(value).orElse(0L);
    }

    private static long[] sideTicks(Object raw, String unit) {
        Map<?, ?> sides = (Map<?, ?>) raw;
        long[] ticks = new long[3];
        for (int axis = 0; axis < 3; axis++) {
            ticks[axis] = MeasureReading.ticks(sides.get(SIDES.get(axis)), Kind.LENGTH, unit);
        }
        return ticks;
    }

    private static void accessDirections(Object raw, String where) {
        List<?> directions = listAt(raw, where);
        for (int index = 0; index < directions.size(); index++) {
            oneOf(directions.get(index), where + "/" + index, ACCESS_DIRECTIONS);
        }
    }

    private static void dimensions(Object raw, String where, String unit) {
        Map<?, ?> sides = objectAt(raw, where);
        for (String side : SIDES) {
            measure(required(sides, side, where), where + "/" + side, Kind.LENGTH, unit);
        }
    }

    /**
     * Code-point order, as every engine walks them: a JavaScript object puts integer-like keys
     * first, so insertion order would name a different bad tag there.
     */
    private static void tagLimits(Object raw, String where) {
        Map<?, ?> limits = objectAt(raw, where);
        List<String> tags = new ArrayList<>();
        limits.keySet().forEach(tag -> tags.add(tag.toString()));
        tags.sort(SortKey::compareCodePoints);
        for (String tag : tags) {
            integer(limits.get(tag), pointer(where, tag), 1);
        }
    }

    private static void rateTable(Object raw, String where) {
        Map<?, ?> table = objectAt(raw, where);
        for (Map.Entry<String, Long> rule : List.of(Map.entry("weight_brackets_g", 1L), Map.entry("prices_minor", 0L))) {
            Object values = table.get(rule.getKey());
            if (values == null) {
                continue;
            }
            List<?> list = listAt(values, where + "/" + rule.getKey());
            for (int index = 0; index < list.size(); index++) {
                integer(list.get(index), where + "/" + rule.getKey() + "/" + index, rule.getValue());
            }
        }
        optionalInteger(table, "minimum_charge_minor", where, 0);
        optionalInteger(table, "fuel_surcharge_permille", where, 0);
    }

    private static void obstacles(Object raw, String where, String unit) {
        List<?> entries = listAt(raw, where);
        for (int index = 0; index < entries.size(); index++) {
            String at = where + "/" + index;
            Map<?, ?> obstacle = objectAt(entries.get(index), at);
            Object origin = obstacle.get("origin");
            if (origin != null) {
                Map<?, ?> point = objectAt(origin, at + "/origin");
                for (String axis : AXES) {
                    optionalMeasure(point, axis, at + "/origin", Kind.LENGTH, unit);
                }
            }
            dimensions(required(obstacle, "dimensions", at), at + "/dimensions", unit);
        }
    }

    // --------------------------------------------------------------------------- primitives

    private static void integer(Object value, String field, long minimum) {
        if (JsonValue.integer(value).isEmpty()) {
            refuseNonInteger(value, field, minimum);
        }
        if (JsonValue.integer(value).get() < minimum) {
            throw refuse("below_minimum", field, "must be at least " + minimum);
        }
    }

    /** A whole number past 2^53 - 1 is out of range, not mistyped: say which way. */
    private static void refuseNonInteger(Object value, String field, long minimum) {
        BigInteger whole = JsonValue.wholeNumber(value);
        if (whole != null && whole.compareTo(BigInteger.valueOf(minimum)) < 0) {
            throw refuse("below_minimum", field, "must be at least " + minimum);
        }
        if (whole != null) {
            throw refuse("above_maximum", field, "must be at most " + CanonicalJson.MAX_EXACT_MAGNITUDE);
        }
        throw refuse("wrong_type", field, "must be an integer");
    }

    private static void ratio(Object value, String field, Long maximum) {
        if (!JsonValue.isNumber(value)) {
            throw refuse("wrong_type", field, "must be a number");
        }
        if (compare(value, 0) < 0) {
            throw refuse("below_minimum", field, "must be at least 0");
        }
        if (maximum != null && compare(value, maximum) > 0) {
            throw refuse("above_maximum", field, "must be at most " + maximum);
        }
    }

    /** A number against a bound: an integer exactly, a fraction as the double it decodes to. */
    private static int compare(Object number, long bound) {
        if (number instanceof BigDecimal || number instanceof Double) {
            // Plain comparison, so -0.0 is not below zero.
            double value = ((Number) number).doubleValue();
            return value < bound ? -1 : value > bound ? 1 : 0;
        }
        return JsonValue.wholeNumber(number).compareTo(BigInteger.valueOf(bound));
    }

    /** A measure is an integer, a string or {@code {value, unit}}, and never negative. */
    private static void measure(Object value, String field, Kind kind, String unit) {
        boolean scalar = value instanceof Long || value instanceof BigInteger || value instanceof String
                || (value instanceof BigDecimal && JsonValue.integer(value).isPresent());
        if (!scalar && !(value instanceof Map)) {
            throw refuse("wrong_type", field, "must be a measure");
        }
        if (value instanceof Map<?, ?> object) {
            if (!object.containsKey("value")) {
                throw refuse("wrong_type", field, "must be a measure");
            }
            Object stated = object.containsKey("unit") ? object.get("unit") : unit;
            if (!(stated instanceof String text) || !MeasureReading.knowsUnit(kind, text)) {
                throw refuse("invalid_unit", field, "has an unknown unit " + CanonicalJson.spelling(stated));
            }
        }
        Verdict verdict = MeasureReading.verdict(value, kind, unit);
        if (verdict == Verdict.NEGATIVE) {
            throw refuse("negative_measure", field, "cannot be negative");
        }
        if (verdict == Verdict.UNREADABLE) {
            throw refuse("wrong_type", field, "must be a measure");
        }
    }

    private static void oneOf(Object value, String field, List<String> allowed) {
        // List.of refuses to look a null up, and null is never an allowed value.
        if (value == null || !allowed.contains(value)) {
            throw refuse("not_allowed", field, "must be one of " + CanonicalJson.spelling(allowed));
        }
    }

    /**
     * The schema closes this object: a key it does not name is refused, never ignored. The first
     * unknown key in code-point order is named, the order every engine can share. O(k log k).
     */
    private static void knownFields(Map<?, ?> object, String where, Collection<String> known) {
        List<String> unknown = JsonValue.namesOutside(object, known);
        if (!unknown.isEmpty()) {
            throw refuse("not_allowed", pointer(where, unknown.get(0)), "is not a known field");
        }
    }

    private static Map<?, ?> objectAt(Object value, String field) {
        if (value instanceof Map<?, ?> object) {
            return object;
        }
        throw refuse("wrong_type", field, "must be an object");
    }

    private static List<?> listAt(Object value, String field) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw refuse("wrong_type", field, "must be a list");
    }

    // ----------------------------------------------------------------------------- plumbing

    /** An optional member: absent and null are the default; anything else must pass. */
    private static void optionalInteger(Map<?, ?> object, String name, String where, long minimum) {
        Object value = object.get(name);
        if (value != null) {
            integer(value, where + "/" + name, minimum);
        }
    }

    private static void optionalOneOf(Map<?, ?> object, String name, String where, List<String> allowed) {
        Object value = object.get(name);
        if (value != null) {
            oneOf(value, where + "/" + name, allowed);
        }
    }

    private static void optionalRatio(Map<?, ?> object, String name, String where, Long maximum) {
        Object value = object.get(name);
        if (value != null) {
            ratio(value, where + "/" + name, maximum);
        }
    }

    private static void optionalMeasure(Map<?, ?> object, String name, String where, Kind kind, String unit) {
        Object value = object.get(name);
        if (value != null) {
            measure(value, where + "/" + name, kind, unit);
        }
    }

    private static Object required(Map<?, ?> object, String name, String where) {
        Object value = object.get(name);
        if (value == null) {
            throw refuse("missing_field", where + "/" + name, "is required");
        }
        return value;
    }

    private static List<?> requiredList(Map<?, ?> object, String name, String where) {
        return listAt(required(object, name, where), where + "/" + name);
    }

    private static void requiredString(Map<?, ?> object, String name, String where) {
        if (!(required(object, name, where) instanceof String)) {
            throw refuse("wrong_type", where + "/" + name, "must be a string");
        }
    }

    /** Checked once every entry is well formed, so the later of two equal ids is named. */
    private static void requireUniqueIds(List<?> entries, String key) {
        Set<Object> seen = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            Object identifier = ((Map<?, ?>) entries.get(index)).get("id");
            if (!seen.add(identifier)) {
                throw refuse("duplicate_id", "/" + key + "/" + index + "/id",
                        "repeats the id " + CanonicalJson.spelling(identifier));
            }
        }
    }

    private static InvalidRequestException refuse(String reason, String field, String detail) {
        return new InvalidRequestException(reason, field, detail);
    }
}
