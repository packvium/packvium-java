package com.packvium.artifacts;

import com.packvium.execution.ExecutionPlanException;
import com.packvium.execution.Plan;
import com.packvium.serialization.JsonReader;
import com.packvium.support.CanonicalJson;
import com.packvium.support.CanonicalJsonException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The portable operational artifact.
 *
 * <p>docs/OPERATIONAL-ARTIFACTS.md is the contract and {@code packvium.artifacts} is the
 * reference this builder is held to <strong>byte-identical</strong> on {@link #canonicalJson}.
 * An execution plan says what to do first; the artifact is the one document that can also be
 * drawn, printed and traced offline, and it adds nothing a solver decided.
 *
 * <p>It reads the request, the result and the optional loading orders -- the plan's own inputs
 * -- and calls no solver, validator, renderer or clock. The plan inside it is exactly what
 * {@link Plan#build(Map, Map, Map)} emits for the same inputs, and placements are found by the
 * plan's placement reference, never by {@code item_id}.
 *
 * <p>The request and result are decoded JSON values, as {@link JsonReader} yields them or as a
 * caller builds them from {@code Map}, {@code List}, {@code String}, {@code Long},
 * {@code Boolean} and {@code null}. The artifact is an immutable tree of the same kind.
 */
public final class OperationalArtifact {
    public static final String FORMAT = "packvium-operational-artifact/v1";

    /**
     * The suite version of this builder, the same string in all four engines of one release.
     * The engine's own name is deliberately not recorded: four correct builders naming
     * themselves would emit four different documents.
     */
    public static final String SUITE_VERSION = "1.4.0";

    /** The deterministic part of {@code result.algorithm}; {@code duration_ms} is wall-clock time. */
    public static final List<String> SOLVER_FIELDS = List.of(
            "profile", "solver", "seed", "time_limit_reached", "effort_limit_reached");

    private static final List<String> DIMENSION_AXES = List.of("length", "width", "height");
    private static final List<String> POSITION_AXES = List.of("x", "y", "z");
    private static final List<String> RESULT_LISTS = List.of(
            "containers", "unpacked_items", "alternatives", "catalog_versions_used");
    private static final BigInteger MAX_EXACT = BigInteger.valueOf(CanonicalJson.MAX_EXACT_MAGNITUDE);

    private OperationalArtifact() {
    }

    /** {@link #build(Object, Object, Map)} with no engine-computed loading order for any container. */
    public static Map<String, Object> build(Object request, Object result) {
        return build(request, result, Map.of());
    }

    /**
     * Build the artifact for one validated result. O(R + P) for a request of size R and P
     * placements, plus O(N + K log K) to prove it has a canonical spelling.
     *
     * @param request a JSON object
     * @param result a JSON object
     * @param loadingOrders container index to the engine-computed order its placements load in
     * @throws OperationalArtifactException with one of the codes the class documents
     */
    public static Map<String, Object> build(Object request, Object result, Map<Integer, List<Integer>> loadingOrders) {
        if (!(request instanceof Map<?, ?> requestObject)) {
            throw new OperationalArtifactException("invalid_request", "a request is a JSON object");
        }
        if (!(result instanceof Map<?, ?> resultObject)) {
            throw new OperationalArtifactException("invalid_result", "a result is a JSON object");
        }
        requireObjects(resultObject);
        Map<String, Object> plan = plan(requestObject, resultObject, loadingOrders);

        List<?> containers = list(resultObject.get("containers"), "result.containers");
        Object[] units = units(containers);
        Map<String, Object> provenance = provenance(requestObject, resultObject);
        List<Object> geometry = new ArrayList<>(containers.size());
        for (int index = 0; index < containers.size(); index++) {
            geometry.add(geometry(index, containers.get(index)));
        }
        List<?> planContainers = (List<?>) plan.get("containers");
        List<Object> workOrder = new ArrayList<>(containers.size());
        for (int index = 0; index < containers.size(); index++) {
            workOrder.add(workOrderContainer(index, (Map<?, ?>) containers.get(index),
                    (Map<?, ?>) planContainers.get(index), units[0], units[1]));
        }
        Map<String, Object> workOrderDocument = new LinkedHashMap<>();
        workOrderDocument.put("length_unit", units[0]);
        workOrderDocument.put("weight_unit", units[1]);
        workOrderDocument.put("containers", Collections.unmodifiableList(workOrder));

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("format", FORMAT);
        artifact.put("suite_version", SUITE_VERSION);
        artifact.put("provenance", provenance);
        artifact.put("plan", plan);
        artifact.put("geometry", Map.of("containers", Collections.unmodifiableList(geometry)));
        artifact.put("work_order", Collections.unmodifiableMap(workOrderDocument));
        // Refused here rather than when someone serializes it: an artifact that exists must
        // have one spelling in every engine.
        canonicalJson(artifact);
        return Collections.unmodifiableMap(artifact);
    }

    /**
     * Build from the request and result as JSON text.
     *
     * @throws OperationalArtifactException {@code invalid_json} when either text does not parse
     */
    public static Map<String, Object> fromJson(String requestJson, String resultJson,
            Map<Integer, List<Integer>> loadingOrders) {
        return build(parse(requestJson), parse(resultJson), loadingOrders);
    }

    /** {@link #fromJson(String, String, Map)} with no engine-computed loading order. */
    public static Map<String, Object> fromJson(String requestJson, String resultJson) {
        return fromJson(requestJson, resultJson, Map.of());
    }

    /**
     * Decode JSON text -- a request, a result or a stored artifact.
     *
     * @throws OperationalArtifactException {@code invalid_json}
     */
    public static Object parse(String json) {
        try {
            return JsonReader.parse(json);
        } catch (IllegalArgumentException error) {
            throw new OperationalArtifactException("invalid_json", "not JSON text: " + error.getMessage(), error);
        }
    }

    /**
     * The artifact's RFC 8785 canonical form, the bytes four engines are compared on.
     *
     * @throws OperationalArtifactException with the canonical writer's code
     */
    public static String canonicalJson(Object artifact) {
        try {
            return CanonicalJson.encode(artifact);
        } catch (CanonicalJsonException error) {
            throw new OperationalArtifactException(error.errorCode(), error.getMessage(), error);
        }
    }

    private static Map<String, Object> plan(Map<?, ?> request, Map<?, ?> result,
            Map<Integer, List<Integer>> loadingOrders) {
        try {
            return Plan.build(stringKeyed(request), stringKeyed(result), loadingOrders);
        } catch (ExecutionPlanException error) {
            throw new OperationalArtifactException("invalid_plan_input", error.getMessage(), error);
        }
    }

    // ---------------------------------------------------------------------------- provenance

    private static Map<String, Object> provenance(Map<?, ?> request, Map<?, ?> result) {
        Map<String, Object> solver = solver(result.get("algorithm"));
        List<?> catalogs = list(result.get("catalog_versions_used"), "result.catalog_versions_used");
        catalogs.forEach(OperationalArtifact::catalog);
        Map<String, Object> provenance = new LinkedHashMap<>();
        // Embedded, not digested: only the request itself lets someone replay the artifact
        // without a lookup.
        provenance.put("request", frozen(request));
        provenance.put("catalog_versions_used", frozen(catalogs));
        provenance.put("solver", solver);
        provenance.put("replay", replay(solver));
        return Collections.unmodifiableMap(provenance);
    }

    /**
     * The result schema's closed catalog reference. The work order prints these fields, so a
     * mistyped one would print differently in every engine.
     */
    private static void catalog(Object catalog) {
        if (!(field(catalog, "catalog_id", "catalog_versions_used[]") instanceof String)) {
            throw new OperationalArtifactException("invalid_result", "catalog_versions_used[].catalog_id is not a string");
        }
        for (String name : List.of("version", "effective_at", "resolved_at")) {
            if (!isInteger(field(catalog, name, "catalog_versions_used[]"))) {
                throw new OperationalArtifactException("invalid_result",
                        "catalog_versions_used[]." + name + " is not an integer");
            }
        }
    }

    private static Map<String, Object> solver(Object algorithm) {
        if (algorithm == null) {
            return null;
        }
        if (!(algorithm instanceof Map<?, ?> record)) {
            throw new OperationalArtifactException("invalid_result", "result.algorithm is not an object");
        }
        List<String> missing = SOLVER_FIELDS.stream().filter(name -> !record.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new OperationalArtifactException("invalid_result", "result.algorithm has no " + String.join(", ", missing));
        }
        for (String flag : List.of("time_limit_reached", "effort_limit_reached")) {
            if (!(record.get(flag) instanceof Boolean)) {
                throw new OperationalArtifactException("invalid_result", "result.algorithm." + flag + " is not a boolean");
            }
        }
        Map<String, Object> solver = new LinkedHashMap<>();
        SOLVER_FIELDS.forEach(name -> solver.put(name, frozen(record.get(name))));
        return Collections.unmodifiableMap(solver);
    }

    /**
     * {@code exact} only when a replay must reproduce the result. A search stopped by wall-clock
     * time cannot be reproduced, and a result that does not say how it was solved cannot be
     * promised to; claiming otherwise would be softening a proof level by another name.
     */
    private static Map<String, Object> replay(Map<String, Object> solver) {
        Map<String, Object> replay = new LinkedHashMap<>();
        if (solver == null) {
            replay.put("level", "not_guaranteed");
            replay.put("because", "provenance.solver");
        } else if ((Boolean) solver.get("time_limit_reached")) {
            replay.put("level", "not_guaranteed");
            replay.put("because", "provenance.solver.time_limit_reached");
        } else {
            replay.put("level", "exact");
            replay.put("because", null);
        }
        return Collections.unmodifiableMap(replay);
    }

    // ------------------------------------------------------------------------------ geometry

    private static Map<String, Object> geometry(int index, Object container) {
        Map<String, Object> innerDimensions = tickDimensions(field(container, "inner_dimensions", "containers[" + index + "]"));
        List<Object> placements = new ArrayList<>();
        for (Object placement : placements(index, container)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("placement", Plan.placementReference(index, stringKeyed((Map<?, ?>) placement)));
            entry.put("dimensions", tickDimensions(field(placement, "dimensions", "containers[" + index + "].placements[]")));
            placements.add(Collections.unmodifiableMap(entry));
        }
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("container_index", (long) index);
        geometry.put("inner_dimensions", innerDimensions);
        geometry.put("placements", Collections.unmodifiableList(placements));
        return Collections.unmodifiableMap(geometry);
    }

    /**
     * Lengths as decimal strings of ticks: the scene contract's spelling, and one no engine has
     * to hold as a native number.
     */
    private static Map<String, Object> tickDimensions(Object dimensions) {
        Map<String, Object> ticks = new LinkedHashMap<>();
        for (String axis : DIMENSION_AXES) {
            ticks.put(axis, ticks(field(dimensions, axis, "dimensions")));
        }
        return Collections.unmodifiableMap(ticks);
    }

    private static String ticks(Object scalar) {
        Object ticks = field(scalar, "ticks", "exact scalar");
        if (!isInteger(ticks)) {
            throw new OperationalArtifactException("invalid_result", "ticks " + ticks + " is not an integer");
        }
        BigInteger value = ticks instanceof BigInteger big ? big : BigInteger.valueOf(((Number) ticks).longValue());
        // Written as a string, so the canonical writer never sees it as a number; the range is
        // checked here instead, because JavaScript has already rounded such a value while parsing.
        if (value.abs().compareTo(MAX_EXACT) > 0) {
            throw new OperationalArtifactException("number_out_of_range",
                    "ticks " + value + " is beyond what every engine holds exactly");
        }
        return value.toString();
    }

    // ---------------------------------------------------------------------------- work order

    /**
     * The display units, read from the result rather than re-derived from request defaults: the
     * result already rendered every value in them.
     */
    private static Object[] units(List<?> containers) {
        if (containers.isEmpty()) {
            return new Object[] {null, null};
        }
        Object first = containers.get(0);
        Object inner = field(first, "inner_dimensions", "containers[0]");
        Object length = field(field(inner, "length", "inner_dimensions"), "unit", "length");
        Object weight = field(field(first, "payload_weight", "containers[0]"), "unit", "payload_weight");
        return new Object[] {frozen(length), frozen(weight)};
    }

    /**
     * One line per plan step, in plan step order: the order is the plan's, looked up, never
     * derived a second time.
     */
    private static Map<String, Object> workOrderContainer(int index, Map<?, ?> container, Map<?, ?> planContainer,
            Object lengthUnit, Object weightUnit) {
        Map<List<Object>, Object> byReference = placementsByReference(index, container);
        List<Object> lines = new ArrayList<>();
        for (Object step : (List<?>) planContainer.get("steps")) {
            Map<?, ?> planStep = (Map<?, ?>) step;
            Map<?, ?> reference = (Map<?, ?>) planStep.get("placement");
            Object placement = byReference.get(referenceKey(reference));
            Map<String, Object> line = new LinkedHashMap<>();
            if (planStep.containsKey("sequence")) {
                line.put("sequence", planStep.get("sequence"));
            }
            Object position = field(placement, "position", "placement");
            Object dimensions = field(placement, "dimensions", "placement");
            line.put("placement", reference);
            line.put("position", renderedValues(position, POSITION_AXES, "position", lengthUnit));
            line.put("dimensions", renderedValues(dimensions, DIMENSION_AXES, "dimensions", lengthUnit));
            lines.add(Collections.unmodifiableMap(line));
        }
        Map<String, Object> workOrder = new LinkedHashMap<>();
        workOrder.put("container_index", (long) index);
        workOrder.put("container_type", frozen(container.get("container_type")));
        workOrder.put("payload_weight", value(field(container, "payload_weight", "containers[" + index + "]"), weightUnit));
        workOrder.put("gross_weight", value(field(container, "gross_weight", "containers[" + index + "]"), weightUnit));
        workOrder.put("lines", Collections.unmodifiableList(lines));
        return Collections.unmodifiableMap(workOrder);
    }

    private static Map<String, Object> renderedValues(Object values, List<String> axes, String where, Object unit) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (String axis : axes) {
            rendered.put(axis, value(field(values, axis, where), unit));
        }
        return Collections.unmodifiableMap(rendered);
    }

    /** A lookup table only: its iteration order never reaches output. */
    private static Map<List<Object>, Object> placementsByReference(int index, Object container) {
        List<?> placements = placements(index, container);
        Map<List<Object>, Object> found = new HashMap<>();
        for (Object placement : placements) {
            found.put(referenceKey(Plan.placementReference(index, stringKeyed((Map<?, ?>) placement))), placement);
        }
        if (found.size() != placements.size()) {
            throw new OperationalArtifactException("invalid_result",
                    "two placements in container " + index + " share an origin, type and orientation");
        }
        return found;
    }

    /** The (item type, orientation, x, y, z) tuple the reference keys on; nulls allowed. */
    private static List<Object> referenceKey(Map<?, ?> reference) {
        Map<?, ?> ticks = (Map<?, ?>) reference.get("position_ticks");
        return Arrays.asList(reference.get("item_type"), reference.get("orientation"),
                ticks.get("x"), ticks.get("y"), ticks.get("z"));
    }

    /** The result's rendered value, copied and never re-rendered. */
    private static String value(Object scalar, Object unit) {
        Object found = field(scalar, "unit", "exact scalar");
        if (!Objects.equals(found, unit)) {
            throw new OperationalArtifactException("mixed_units",
                    "a value in '" + found + "' where the result uses '" + unit + "'");
        }
        if (!(field(scalar, "value", "exact scalar") instanceof String rendered)) {
            throw new OperationalArtifactException("invalid_result", "a rendered value is not a string");
        }
        return rendered;
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Every list entry the artifact and its plan read is an object, checked before either reads
     * one, so a malformed result is refused by name in every engine instead of failing
     * wherever each language first touches it.
     */
    private static void requireObjects(Map<?, ?> result) {
        for (String name : RESULT_LISTS) {
            for (Object entry : list(result.get(name), "result." + name)) {
                if (!(entry instanceof Map)) {
                    throw new OperationalArtifactException("invalid_result", "result." + name + " holds a non-object entry");
                }
            }
        }
        List<?> containers = list(result.get("containers"), "result.containers");
        for (int index = 0; index < containers.size(); index++) {
            for (Object placement : placements(index, containers.get(index))) {
                if (!(placement instanceof Map)) {
                    throw new OperationalArtifactException("invalid_result",
                            "containers[" + index + "].placements holds a non-object entry");
                }
            }
        }
    }

    private static List<?> placements(int index, Object container) {
        return list(((Map<?, ?>) container).get("placements"), "containers[" + index + "].placements");
    }

    private static List<?> list(Object value, String where) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> elements)) {
            throw new OperationalArtifactException("invalid_result", where + " is not a list");
        }
        return elements;
    }

    private static Object field(Object object, String name, String where) {
        if (!(object instanceof Map<?, ?> members) || !members.containsKey(name)) {
            throw new OperationalArtifactException("invalid_result", where + " has no " + name);
        }
        return members.get(name);
    }

    /** A JSON integer, as the reference's {@code isinstance(value, int)} without {@code bool}. */
    private static boolean isInteger(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger;
    }

    /** A deep, unmodifiable copy that keeps every key as it was, so the canonical writer still judges it. */
    private static Object frozen(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, frozen(member)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>(elements.size());
            elements.forEach(element -> copy.add(frozen(element)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * The plan adapter's view of a JSON object. A key that is not a string is never looked up
     * by it, and the canonical writer refuses such a key before the artifact is returned.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> stringKeyed(Map<?, ?> object) {
        return (Map<String, ?>) object;
    }
}
