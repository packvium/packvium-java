package com.packvium.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.execution.Plan;
import com.packvium.serialization.JsonReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The operational artifact and its exports, holding the properties PHP's
 * OperationalArtifactTest holds at v1.3.0.
 *
 * <p>The assertions follow what the operational-artifact contract promises the artifact will not do:
 * re-derive the plan or its order, address a placement by {@code item_id}, let wall-clock time
 * in, claim an exact replay it cannot give, or re-render a value the result already rendered.
 * Byte equality with Python is asserted by the artifact conformance run over the tag's corpus and
 * edge cases, not here.
 */
class OperationalArtifactTest {
    private static final long TICKS_PER_MM = 16000;
    private static final Map<Integer, List<Integer>> REVERSED = Map.of(0, List.of(1, 0));

    private static Map<String, Object> request() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "box");
        item.put("dimensions", Map.of("length", 10L, "width", 10L, "height", 10L));
        item.put("minimum_support_ratio", new BigDecimal("0.25"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("items", List.of(item));
        request.put("containers", List.of(Map.of("id", "crate",
                "inner_dimensions", Map.of("length", 100L, "width", 100L, "height", 100L))));
        return request;
    }

    private static Map<String, Object> scalar(long ticks, String unit) {
        long perUnit = unit.equals("mm") ? TICKS_PER_MM : 8000;
        return new LinkedHashMap<>(Map.of("ticks", ticks, "unit", unit, "value", Long.toString(ticks / perUnit)));
    }

    private static Map<String, Object> placement(String itemType, long xMm, long lengthMm) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("item_id", itemType + "#" + xMm);
        placement.put("item_type", itemType);
        placement.put("orientation", "LWH");
        placement.put("position", new LinkedHashMap<>(Map.of("x", scalar(xMm * TICKS_PER_MM, "mm"),
                "y", scalar(0, "mm"), "z", scalar(0, "mm"))));
        placement.put("dimensions", new LinkedHashMap<>(Map.of("length", scalar(lengthMm * TICKS_PER_MM, "mm"),
                "width", scalar(10 * TICKS_PER_MM, "mm"), "height", scalar(10 * TICKS_PER_MM, "mm"))));
        placement.put("support_ratio", "1.000000");
        placement.put("top_load", scalar(0, "g"));
        return placement;
    }

    private static Map<String, Object> algorithm() {
        Map<String, Object> algorithm = new LinkedHashMap<>();
        algorithm.put("profile", "balanced");
        algorithm.put("solver", "extreme_point");
        algorithm.put("seed", 7L);
        algorithm.put("duration_ms", 41L);
        algorithm.put("time_limit_reached", false);
        algorithm.put("effort_limit_reached", false);
        return algorithm;
    }

    private static Map<String, Object> result(List<Map<String, Object>> placements, List<Object> unpacked) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("id", "crate#1");
        container.put("container_type", "crate");
        container.put("inner_dimensions", new LinkedHashMap<>(Map.of("length", scalar(100 * TICKS_PER_MM, "mm"),
                "width", scalar(100 * TICKS_PER_MM, "mm"), "height", scalar(100 * TICKS_PER_MM, "mm"))));
        container.put("payload_weight", scalar(8000, "g"));
        container.put("gross_weight", scalar(16000, "g"));
        container.put("volume_utilization", "0.002000");
        container.put("placements", new ArrayList<Object>(placements));
        Map<String, Object> catalog = new LinkedHashMap<>(Map.of("catalog_id", "cartons", "version", 3L,
                "effective_at", 10L, "resolved_at", 11L));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "feasible");
        result.put("objective", "default");
        result.put("score", List.of(1L, 0L, 250L));
        result.put("feasibility", Map.of("code", "all_items_packed"));
        result.put("optimality", null);
        result.put("containers", new ArrayList<Object>(List.of(container)));
        result.put("unpacked_items", unpacked);
        result.put("catalog_versions_used", new ArrayList<Object>(List.of(catalog)));
        result.put("algorithm", algorithm());
        return result;
    }

    private static Map<String, Object> result() {
        return result(List.of(placement("box", 0, 10), placement("tin", 10, 5)), List.of());
    }

    private static Map<String, Object> result(UnaryOperator<Map<String, Object>> change) {
        return change.apply(result());
    }

    private static Map<String, Object> artifact(List<Object> unpacked) {
        return OperationalArtifact.build(request(),
                result(List.of(placement("box", 0, 10), placement("tin", 10, 5)), unpacked), REVERSED);
    }

    private static Map<String, Object> artifact() {
        return artifact(List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> containerOf(Map<String, Object> result) {
        return (Map<String, Object>) ((List<?>) result.get("containers")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstPlacement(Map<String, Object> result) {
        return (Map<String, Object>) ((List<?>) containerOf(result).get("placements")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Object at(Object tree, Object... path) {
        Object node = tree;
        for (Object step : path) {
            node = step instanceof Integer index ? ((List<?>) node).get(index) : ((Map<?, ?>) node).get(step);
        }
        return node;
    }

    /** A deep, mutable copy of a JSON tree, to edit a stored artifact as a caller might. */
    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, mutable(member)));
            return copy;
        }
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>();
            elements.forEach(element -> copy.add(mutable(element)));
            return copy;
        }
        return value;
    }

    private static Map<String, Object> edited(UnaryOperator<Map<String, Object>> change) {
        return change.apply(asMap(mutable(artifact())));
    }

    private static String code(Runnable refused) {
        return assertThrows(OperationalArtifactException.class, refused::run).errorCode();
    }

    private static final List<Function<Object, String>> EXPORTS = List.of(
            ArtifactExports::json, ArtifactExports::csv, ArtifactExports::workOrderHtml);
    private static final List<Function<Object, String>> TEXT_EXPORTS = List.of(
            ArtifactExports::csv, ArtifactExports::workOrderHtml);

    // ---------------------------------------------------------------------------- the document

    @Test
    void thePlanInsideIsExactlyThePlanBuilderOutput() {
        Map<String, Object> artifact = artifact();
        assertEquals(OperationalArtifact.FORMAT, artifact.get("format"));
        assertEquals("1.7.0", artifact.get("suite_version"));
        assertEquals(Plan.canonicalJson(Plan.build(request(), result(), REVERSED)),
                Plan.canonicalJson(asMap(artifact.get("plan"))));
    }

    @Test
    void theRequestIsEmbeddedAsGivenWithItsFractionalNumbers() {
        Map<String, Object> artifact = OperationalArtifact.build(request(), result());
        assertEquals(request(), at(artifact, "provenance", "request"));
        assertTrue(OperationalArtifact.canonicalJson(artifact).contains("\"minimum_support_ratio\":0.25"));
    }

    @Test
    void jsonTextKeepsEmptyObjectsAndNumericNamesInTheRequestAndThePlan() {
        String resultJson = OperationalArtifact.canonicalJson(result(changed -> {
            changed.put("feasibility", Map.of());
            return changed;
        }));
        String json = OperationalArtifact.canonicalJson(OperationalArtifact.fromJson(
                "{\"items\":[],\"containers\":[],\"metadata\":{},\"configuration\":{\"0\":\"a\",\"1\":{}}}", resultJson));
        assertTrue(json.contains("\"configuration\":{\"0\":\"a\",\"1\":{}}"));
        assertTrue(json.contains("\"metadata\":{}"));
        assertTrue(json.contains("\"feasibility\":{}"));
    }

    @Test
    void geometryIsInResultOrderInTickStringsAddressedByPlacementReference() {
        Object container = at(OperationalArtifact.build(request(), result()), "geometry", "containers", 0);
        assertEquals(Map.of("length", "1600000", "width", "1600000", "height", "1600000"),
                at(container, "inner_dimensions"));
        assertEquals("box", at(container, "placements", 0, "placement", "item_type"));
        assertEquals("tin", at(container, "placements", 1, "placement", "item_type"));
        assertEquals("80000", at(container, "placements", 1, "dimensions", "length"));
        assertFalse(OperationalArtifact.canonicalJson(container).contains("item_id"));
    }

    @Test
    void workOrderLinesFollowThePlanStepsAndCopyRenderedValues() {
        Map<String, Object> artifact = artifact();
        Object workOrder = artifact.get("work_order");
        assertEquals(List.of("mm", "g"), List.of(at(workOrder, "length_unit"), at(workOrder, "weight_unit")));
        assertEquals("1", at(workOrder, "containers", 0, "payload_weight"));
        assertEquals("2", at(workOrder, "containers", 0, "gross_weight"));
        assertEquals(List.of(1L, "tin", 2L, "box"), List.of(
                at(workOrder, "containers", 0, "lines", 0, "sequence"),
                at(workOrder, "containers", 0, "lines", 0, "placement", "item_type"),
                at(workOrder, "containers", 0, "lines", 1, "sequence"),
                at(workOrder, "containers", 0, "lines", 1, "placement", "item_type")));
        assertEquals(Map.of("x", "10", "y", "0", "z", "0"), at(workOrder, "containers", 0, "lines", 0, "position"));
        for (int step = 0; step < 2; step++) {
            assertEquals(at(artifact, "plan", "containers", 0, "steps", step, "placement"),
                    at(workOrder, "containers", 0, "lines", step, "placement"));
        }
    }

    @Test
    void withoutALoadingOrderNoLineIsNumbered() {
        for (Object line : (List<?>) at(OperationalArtifact.build(request(), result()), "work_order", "containers", 0, "lines")) {
            assertFalse(((Map<?, ?>) line).containsKey("sequence"));
        }
    }

    @Test
    void aResultWithoutContainersHasNoUnitsAndNoLines() {
        Map<String, Object> result = result(changed -> {
            changed.put("containers", List.of());
            return changed;
        });
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("length_unit", null);
        expected.put("weight_unit", null);
        expected.put("containers", List.of());
        assertEquals(expected, OperationalArtifact.build(request(), result).get("work_order"));
    }

    @Test
    void theArtifactIsImmutable() {
        Map<String, Object> artifact = artifact();
        assertThrows(UnsupportedOperationException.class, () -> artifact.put("format", "x"));
        assertThrows(UnsupportedOperationException.class, () -> asMap(at(artifact, "provenance", "request")).clear());
    }

    // ---------------------------------------------------------------------------- provenance

    @Test
    void theSolverIsTheDeterministicPartOfTheAlgorithmAndWallClockNeverEnters() {
        Map<String, Object> artifact = OperationalArtifact.build(request(), result());
        Map<String, Object> expected = algorithm();
        expected.remove("duration_ms");
        assertEquals(expected, at(artifact, "provenance", "solver"));
        assertEquals(Arrays.asList("exact", null), Arrays.asList(at(artifact, "provenance", "replay", "level"),
                at(artifact, "provenance", "replay", "because")));
        assertEquals("cartons", at(artifact, "provenance", "catalog_versions_used", 0, "catalog_id"));
        assertFalse(OperationalArtifact.canonicalJson(artifact).contains("duration_ms"));
    }

    @Test
    void aSearchStoppedByTheClockIsNotPromisedAnExactReplay() {
        Map<String, Object> result = result(changed -> {
            asMap(changed.get("algorithm")).put("time_limit_reached", true);
            return changed;
        });
        assertEquals(Map.of("level", "not_guaranteed", "because", "provenance.solver.time_limit_reached"),
                at(OperationalArtifact.build(request(), result), "provenance", "replay"));
    }

    @Test
    void aResultThatDoesNotSayHowItWasSolvedIsNotPromisedOneEither() {
        Map<String, Object> result = result(changed -> {
            changed.remove("algorithm");
            return changed;
        });
        Object provenance = OperationalArtifact.build(request(), result).get("provenance");
        assertNull(at(provenance, "solver"));
        assertEquals(Map.of("level", "not_guaranteed", "because", "provenance.solver"), at(provenance, "replay"));
    }

    /** {@code replayLevel} answers without building the artifact, by the same rule. */
    @Test
    void theReplayLevelIsThePromiseTheArtifactWouldMake() {
        Map<String, Object> stopped = result(changed -> {
            asMap(changed.get("algorithm")).put("time_limit_reached", true);
            return changed;
        });
        Map<String, Object> unsaid = result(changed -> {
            changed.remove("algorithm");
            return changed;
        });
        for (Map<String, Object> result : List.of(result(), stopped, unsaid)) {
            assertEquals(at(OperationalArtifact.build(request(), result), "provenance", "replay", "level"),
                    OperationalArtifact.replayLevel(result));
        }
        assertEquals("exact", OperationalArtifact.replayLevel(result()));
        assertEquals("invalid_result",
                assertThrows(OperationalArtifactException.class, () -> OperationalArtifact.replayLevel(List.of()))
                        .errorCode());
    }

    // ------------------------------------------------------------------------------ refusals

    @Test
    void aRequestOrResultThatIsNotAnObjectIsRefused() {
        for (Object request : List.of(List.of(), List.of("a"), "text")) {
            assertEquals("invalid_request", code(() -> OperationalArtifact.build(request, result())));
        }
        assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), List.of(1L, 2L))));
    }

    @Test
    void aLoadingOrderThatIsNotAPermutationIsRefusedByThePlan() {
        assertEquals("invalid_plan_input",
                code(() -> OperationalArtifact.build(request(), result(), Map.of(0, List.of(0, 0)))));
    }

    @Test
    void valuesInTwoUnitsAreRefusedRatherThanPrintedAsOne() {
        Map<String, Object> result = result(changed -> {
            asMap(asMap(firstPlacement(changed).get("position")).get("x")).put("unit", "cm");
            return changed;
        });
        assertEquals("mixed_units", code(() -> OperationalArtifact.build(request(), result)));
    }

    @Test
    void twoPlacementsWithOneReferenceAreRefused() {
        Map<String, Object> result = result(List.of(placement("box", 0, 10), placement("box", 0, 10)), List.of());
        assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), result)));
    }

    @Test
    void aContainerWithoutInnerDimensionsIsRefused() {
        Map<String, Object> result = result(changed -> {
            containerOf(changed).remove("inner_dimensions");
            return changed;
        });
        assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), result)));
    }

    @Test
    void anAlgorithmRecordThatIsIncompleteOrMistypedIsRefused() {
        Map<String, Object> mistyped = algorithm();
        mistyped.put("time_limit_reached", "no");
        for (Object algorithm : List.of(Map.of("profile", "fast"), mistyped, "fast")) {
            Map<String, Object> result = result(changed -> {
                changed.put("algorithm", algorithm);
                return changed;
            });
            assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), result)));
        }
    }

    @Test
    void aRequestNumberJavascriptCannotHoldIsRefused() {
        Map<String, Object> request = request();
        request.put("metadata", Map.of("order", 9007199254740992L));
        assertEquals("number_out_of_range", code(() -> OperationalArtifact.build(request, result())));
    }

    @Test
    void aLoneSurrogateIsRefusedWhereverItSits() {
        // JsonReader holds a lone surrogate (PHP's decoder refuses it as invalid_json); the
        // canonical writer then refuses the artifact, so it is never built either way.
        String text = "{\"items\":[],\"containers\":[],\"metadata\":{\"bad\":\"\\ud800\"}}";
        assertEquals("invalid_string",
                code(() -> OperationalArtifact.fromJson(text, OperationalArtifact.canonicalJson(result()))));
    }

    @Test
    void aContainerThatIsNotAnObjectIsRefusedByNameBeforeAnythingReadsIt() {
        Map<String, Object> notAnObject = result(changed -> {
            changed.put("containers", List.of("crate"));
            return changed;
        });
        assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), notAnObject)));
        Map<String, Object> placementNotAnObject = result(changed -> {
            @SuppressWarnings("unchecked")
            List<Object> placements = (List<Object>) containerOf(changed).get("placements");
            placements.add("box");
            return changed;
        });
        assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), placementNotAnObject)));
    }

    @Test
    void geometryTicksJavascriptCannotHoldAreRefused() {
        // Geometry writes ticks as strings, so the canonical writer never sees them as numbers.
        Map<String, Object> result = result(changed -> {
            asMap(asMap(firstPlacement(changed).get("dimensions")).get("length")).put("ticks", 9007199254740992L);
            return changed;
        });
        assertEquals("number_out_of_range", code(() -> OperationalArtifact.build(request(), result)));
    }

    /** Python's {@code int(1e300)} is exact, so the canonical writer, not the plan, refuses it. */
    @Test
    void aPositionTickPastSixtyFourBitsIsOutOfRangeNotABadPlan() {
        for (String axis : List.of("x", "y", "z")) {
            Map<String, Object> result = result(changed -> {
                asMap(asMap(firstPlacement(changed).get("position")).get(axis)).put("ticks", new BigDecimal("1e300"));
                return changed;
            });
            assertEquals("number_out_of_range", code(() -> OperationalArtifact.build(request(), result)), axis);
        }
    }

    @Test
    void aCatalogReferenceOfTheWrongTypeIsRefused() {
        for (Map.Entry<String, Object> change : List.of(Map.entry("version", (Object) new BigDecimal("1.5")),
                Map.entry("version", (Object) true), Map.entry("catalog_id", (Object) 3L))) {
            Map<String, Object> result = result(changed -> {
                asMap(((List<?>) changed.get("catalog_versions_used")).get(0)).put(change.getKey(), change.getValue());
                return changed;
            });
            assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), result)));
        }
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        String resultJson = OperationalArtifact.canonicalJson(result());
        for (String text : List.of("{", "\ufeff{}", "{\"a\":1,}")) {
            assertEquals("invalid_json", code(() -> OperationalArtifact.fromJson(text, resultJson)));
        }
    }

    @Test
    void aResultOfTheWrongShapeIsRefusedByName() {
        List<UnaryOperator<Map<String, Object>>> changes = List.of(
                changed -> {
                    changed.put("containers", Map.of("crate", containerOf(changed)));
                    return changed;
                },
                changed -> {
                    asMap(asMap(firstPlacement(changed).get("dimensions")).get("length")).put("ticks", "160000");
                    return changed;
                },
                changed -> {
                    asMap(asMap(containerOf(changed).get("inner_dimensions")).get("width")).put("ticks", new BigDecimal("1.5"));
                    return changed;
                },
                changed -> {
                    asMap(containerOf(changed).get("payload_weight")).put("value", 1L);
                    return changed;
                });
        for (UnaryOperator<Map<String, Object>> change : changes) {
            Map<String, Object> result = result(change);
            assertEquals("invalid_result", code(() -> OperationalArtifact.build(request(), result)));
        }
    }

    @Test
    void theBuilderImportsNoSolverValidatorRendererOrClock() throws IOException {
        List<String> sources = List.of("artifacts/OperationalArtifact.java", "artifacts/ArtifactExports.java",
                "artifacts/OperationalArtifactException.java", "support/CanonicalJson.java", "support/ShortestFloat.java");
        List<String> forbidden = List.of("com.packvium.algorithm", "com.packvium.validation", "com.packvium.Packer",
                "com.packvium.serialization.RequestPacker", "com.packvium.commerce", "java.time", "currentTimeMillis",
                "nanoTime", "java.util.Random");
        for (String source : sources) {
            String code = Files.readString(Path.of("src/main/java/com/packvium", source), StandardCharsets.UTF_8);
            forbidden.forEach(name -> assertFalse(code.contains(name), source + " references " + name));
        }
    }

    // ------------------------------------------------------------------------------- exports

    @Test
    void theJsonExportIsTheCanonicalArtifact() {
        Map<String, Object> artifact = artifact();
        assertEquals(OperationalArtifact.canonicalJson(artifact), ArtifactExports.json(artifact));
        assertEquals(JsonReader.parse(OperationalArtifact.canonicalJson(artifact)),
                JsonReader.parse(ArtifactExports.json(artifact)));
    }

    @Test
    void everyExportRefusesADocumentItCannotRead() {
        Map<String, Object> foreign = edited(document -> {
            document.put("format", "packvium-operational-artifact/v2");
            return document;
        });
        for (Function<Object, String> export : EXPORTS) {
            for (Object document : List.of(foreign, List.of("not", "an", "artifact"), Map.of())) {
                assertEquals("unknown_format", code(() -> export.apply(document)));
            }
        }
    }

    @Test
    void aValueWithNoSingleRenderingIsRefusedRatherThanPrintedFourWays() {
        // Python would print `True`, PHP `1`, JavaScript `true`: none of them is the answer.
        for (Object containerType : List.of(true, new BigDecimal("1.5"), List.of("a"), Map.of())) {
            Map<String, Object> result = result(changed -> {
                containerOf(changed).put("container_type", containerType);
                return changed;
            });
            Map<String, Object> artifact = OperationalArtifact.build(request(), result);
            for (Function<Object, String> export : TEXT_EXPORTS) {
                assertEquals("invalid_value", code(() -> export.apply(artifact)));
            }
        }
        Map<String, Object> result = result(changed -> {
            changed.put("feasibility", Map.of("code", true));
            return changed;
        });
        assertEquals("invalid_value", code(() -> ArtifactExports.workOrderHtml(OperationalArtifact.build(request(), result))));
    }

    /** A fractional score is carried, as Python carries it, and the sheet refuses to print it. */
    @Test
    void aFractionalScoreIsRefusedByTheWorkOrderNotTruncated() {
        Map<String, Object> result = result(changed -> {
            changed.put("score", List.of(new BigDecimal("1.5"), 0L));
            return changed;
        });
        Map<String, Object> artifact = OperationalArtifact.build(request(), result);
        assertTrue(ArtifactExports.json(artifact).contains("\"score\":[1.5,0]"));
        assertEquals("invalid_value", code(() -> ArtifactExports.workOrderHtml(artifact)));
    }

    @Test
    void anIntegralFloatIsReadAsTheIntegerEveryEngineReadsBack() {
        // Exports read through the canonical form, where 2.0 is 2: the sheet's container 3.
        Map<String, Object> artifact = edited(document -> {
            asMap(at(document, "work_order", "containers", 0)).put("container_index", 2.0);
            return document;
        });
        assertTrue(ArtifactExports.workOrderHtml(artifact).contains("<h2>Container 3: crate</h2>"));
    }

    @Test
    void aContainerNumberThatIsNotAnIntegerIsRefusedNotComputed() {
        for (Object index : Arrays.asList(true, false, new BigDecimal("0.5"), 0.5, "0", null)) {
            Map<String, Object> artifact = edited(document -> {
                asMap(at(document, "work_order", "containers", 0)).put("container_index", index);
                return document;
            });
            assertEquals("invalid_value", code(() -> ArtifactExports.workOrderHtml(artifact)));
        }
    }

    @Test
    void aBooleanInAnyPrintedPositionIsRefused() {
        Map<String, Object> index = edited(document -> {
            asMap(at(document, "work_order", "containers", 0, "lines", 0, "placement")).put("container_index", true);
            return document;
        });
        assertEquals("invalid_value", code(() -> ArtifactExports.csv(index)));
        Map<String, Object> sequence = edited(document -> {
            asMap(at(document, "work_order", "containers", 0, "lines", 0)).put("sequence", false);
            return document;
        });
        for (Function<Object, String> export : TEXT_EXPORTS) {
            assertEquals("invalid_value", code(() -> export.apply(sequence)));
        }
    }

    @Test
    void anExportRefusesADocumentMissingWhatTheFormatRequires() {
        Map<String, Object> missing = edited(document -> {
            document.remove("work_order");
            return document;
        });
        Map<String, Object> objectForList = edited(document -> {
            Map<String, Object> workOrder = asMap(document.get("work_order"));
            workOrder.put("containers", Map.of("first", at(workOrder, "containers", 0)));
            return document;
        });
        Map<String, Object> unplacedObject = edited(document -> {
            asMap(document.get("plan")).put("unplaced", Map.of());
            return document;
        });
        assertEquals("unknown_format", code(() -> ArtifactExports.csv(missing)));
        assertEquals("unknown_format", code(() -> ArtifactExports.workOrderHtml(missing)));
        assertEquals("unknown_format", code(() -> ArtifactExports.csv(objectForList)));
        assertEquals("unknown_format", code(() -> ArtifactExports.workOrderHtml(unplacedObject)));
    }

    @Test
    void theCsvHasAHeaderOneRowPerStepInOrderAndOnePerUnplacedItem() {
        List<Object> unpacked = List.of(Map.of("item_id", "jack#1", "item_type", "jack",
                "reason", "no_compatible_container_dimensions", "details", List.of(), "proof", Map.of("level", "proven")));
        List<String> rows = List.of(ArtifactExports.csv(artifact(unpacked)).split("\r\n", -1));
        assertEquals(String.join(",", ArtifactExports.CSV_COLUMNS), rows.get(0));
        assertEquals("step,0,crate,1,tin,LWH,160000,0,0,10,0,0,5,10,10,mm,,", rows.get(1));
        assertEquals("step,0,crate,2,box,LWH,0,0,0,0,0,0,10,10,10,mm,,", rows.get(2));
        assertEquals("unplaced,,,,jack,,,,,,,,,,,,no_compatible_container_dimensions,proven", rows.get(3));
        assertEquals("", rows.get(4));
        assertEquals(5, rows.size());
    }

    @Test
    void aCsvFieldIsQuotedOnlyWhenItMustBeAndNeverRewritten() {
        Map<String, Object> result = result(List.of(placement("a,\"b\"", 0, 10), placement("=SUM(A1)", 10, 10),
                placement("two\nlines", 20, 10), placement("cr\rhere", 30, 10)), List.of());
        String text = ArtifactExports.csv(OperationalArtifact.build(request(), result));
        assertTrue(text.contains(",\"a,\"\"b\"\"\","));
        assertTrue(text.contains(",=SUM(A1),"));
        assertTrue(text.contains(",\"two\nlines\","));
        assertTrue(text.contains(",\"cr\rhere\","));
    }

    @Test
    void theWorkOrderIsSelfContainedEscapedAndListsEveryStep() {
        Map<String, Object> result = result(List.of(placement("<b>&'\"", 0, 10), placement("tin", 10, 5)), List.of());
        String html = ArtifactExports.workOrderHtml(OperationalArtifact.build(request(), result, REVERSED));
        assertTrue(html.startsWith("<!DOCTYPE html>\n"));
        assertTrue(html.endsWith("</html>\n"));
        for (String forbidden : List.of("<script", "http", " src=", "<b>&'")) {
            assertFalse(html.contains(forbidden), "the work order holds " + forbidden);
        }
        assertTrue(html.contains("&lt;b&gt;&amp;&#39;&quot;"));
        assertEquals(2, html.split("&#9744;", -1).length - 1);
        for (String expected : List.of("Order: loading.", "extreme_point", "cartons v3")) {
            assertTrue(html.contains(expected), "the work order lacks " + expected);
        }
        assertTrue(html.chars().allMatch(character -> character < 0x80));
    }

    @Test
    void theWorkOrderSaysWhenThereIsNoOrderAndWhenEverythingWasPacked() {
        String html = ArtifactExports.workOrderHtml(OperationalArtifact.build(request(), result()));
        assertTrue(html.contains("Order: unavailable."));
        assertTrue(html.contains("<p>Every item was packed.</p>"));
    }

    @Test
    void exportsAreDeterministicAndLeaveTheArtifactUntouched() {
        Map<String, Object> artifact = artifact();
        String before = OperationalArtifact.canonicalJson(artifact);
        for (Function<Object, String> export : EXPORTS) {
            assertEquals(export.apply(artifact), export.apply(artifact));
        }
        assertEquals(before, OperationalArtifact.canonicalJson(artifact));
    }

    @Test
    void anArtifactReadBackFromItsJsonExportsTheSameBytes() {
        Map<String, Object> artifact = artifact(List.of(Map.of("item_id", "jack#1", "item_type", "jack",
                "reason", "too_heavy", "details", List.of(), "proof", Map.of("level", "observed"))));
        Object stored = OperationalArtifact.parse(ArtifactExports.json(artifact));
        for (Function<Object, String> export : EXPORTS) {
            assertEquals(export.apply(artifact), export.apply(stored));
        }
    }
}
