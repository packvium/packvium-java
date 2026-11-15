package com.packvium.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.serialization.JsonReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The execution-plan adapter, holding the properties PHP's ExecutionPlanTest holds.
 *
 * <p>The cross-language equality itself is asserted by the execution-plan conformance run over a
 * tag's golden results, not here: a committed expected string would only prove that Java still
 * agrees with a string. The alternatives rules run on a constructed result because the goldens store the
 * canonical projection, which drops the field.
 */
class PlanTest {
    private static Map<String, Object> scalar(long ticks) {
        // Both spellings, as a real result carries them: `value` is a rendering and `ticks` is
        // the number. Supplying only one could not catch a reference built on the wrong one.
        return Map.of("ticks", ticks, "value", Long.toString(ticks / 16000), "unit", "mm");
    }

    private static Map<String, Object> placement(String itemType, long x, long y, long z) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("item_id", itemType + "#" + x + y + z);
        placement.put("item_type", itemType);
        placement.put("orientation", "LWH");
        placement.put("position", Map.of("x", scalar(x), "y", scalar(y), "z", scalar(z)));
        placement.put("dimensions", Map.of("length", scalar(1600000), "width", scalar(1600000),
                "height", scalar(1600000)));
        placement.put("top_load", 0L);
        return placement;
    }

    private static Map<String, Object> result(Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "feasible");
        result.put("objective", "default");
        result.put("score", List.of(0L, 1L, 0L, 0L, 1000000L));
        result.put("feasibility", Map.of("code", "feasible"));
        result.put("optimality", Map.of("code", "not_proven"));
        result.put("containers", List.of(Map.of("id", "box#1", "container_type", "box",
                "volume_utilization", "0.500000",
                "placements", List.of(placement("cube", 0, 0, 0), placement("cube", 1600000, 0, 0)))));
        result.put("unpacked_items", List.of());
        result.put("alternatives", List.of());
        result.putAll(overrides);
        return result;
    }

    private static Map<String, Object> result() {
        return result(Map.of());
    }

    private static Map<String, Object> withAlternative(List<Long> score) {
        return result(Map.of("alternatives", List.of(Map.of("status", "feasible", "score", score))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object tree, Object... path) {
        Object node = tree;
        for (Object step : path) {
            node = step instanceof Integer index ? ((List<?>) node).get(index) : ((Map<?, ?>) node).get(step);
        }
        return (T) node;
    }

    @Test
    void withoutAnInjectedOrderThePlanSaysSo() {
        Map<String, Object> container = at(Plan.build(Map.of(), result()), "containers", 0);
        assertEquals("unavailable", container.get("order"));
        // Every placement is still listed, so nothing is hidden -- but no step numbers, because
        // array position is an artifact of candidate iteration.
        List<Map<String, Object>> steps = at(container, "steps");
        assertEquals(2, steps.size());
        steps.forEach(step -> assertFalse(step.containsKey("sequence")));
    }

    @Test
    void anInjectedOrderIsUsedVerbatim() {
        Map<String, Object> container = at(Plan.build(Map.of(), result(), Map.of(0, List.of(1, 0))), "containers", 0);
        assertEquals("loading", container.get("order"));
        assertEquals(1L, (Long) at(container, "steps", 0, "sequence"));
        assertEquals(2L, (Long) at(container, "steps", 1, "sequence"));
        // Step 1 is the placement the caller put first, not the one the result listed first.
        assertEquals(1600000L, (Long) at(container, "steps", 0, "placement", "position_ticks", "x"));
    }

    @Test
    void anOrderThatIsNotAPermutationIsRefused() {
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.build(Map.of(), result(), Map.of(0, List.of(0, 0))));
        assertTrue(error.getMessage().contains("permutation"));
    }

    @Test
    void thePlacementReferenceDoesNotUseItemId() {
        // `item_id` exists and conformance/canonical.py drops it as an instance count;
        // referencing it would make a plan two correct engines disagree about.
        Map<String, Object> reference = Plan.placementReference(0, placement("cube", 0, 0, 0));
        assertEquals(List.of("container_index", "item_type", "orientation", "position_ticks"),
                new ArrayList<>(reference.keySet()));
    }

    @Test
    void thePlacementReferenceReadsTicksAndNotTheRenderedValue() {
        Map<String, Object> placement = placement("cube", 12345, 0, 0);
        placement.put("position", Map.of("x", Map.of("ticks", 12345L, "value", "wrong"), "y", scalar(0),
                "z", scalar(0)));
        assertEquals(12345L, (Long) at(Plan.placementReference(0, placement), "position_ticks", "x"));
    }

    @Test
    void aPlacementItCannotReferenceIsRefused() {
        Map<String, Object> placement = placement("cube", 0, 0, 0);
        placement.remove("orientation");
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.placementReference(0, placement));
        assertTrue(error.getMessage().contains("missing a field"));
    }

    @Test
    void theLossIsTheFirstDifferingIndexAndNeverABlend() {
        Map<String, Object> facts = at(Plan.build(Map.of(), withAlternative(List.of(0L, 2L, 0L, 0L, 900000L))),
                "alternatives", 0, "facts");
        assertEquals(Map.of("index", 1L, "winner", 1L, "alternative", 2L, "difference", 1L),
                facts.get("first_difference"));
        assertFalse(facts.containsKey("total"));
        assertFalse(facts.containsKey("weighted"));
    }

    @Test
    void theSentenceNamesAnAxisAndClaimsNoCause() {
        Map<String, Object> presentation = at(Plan.build(Map.of(),
                withAlternative(List.of(0L, 2L, 0L, 0L, 900000L))), "alternatives", 0, "presentation");
        String summary = (String) presentation.get("summary");
        assertTrue(summary.contains("axis 1"));
        assertEquals(List.of("score", "alternatives[].score"), presentation.get("cites"));
        for (String causal : List.of("because", "due to", "caused")) {
            assertFalse(summary.toLowerCase(java.util.Locale.ROOT).contains(causal));
        }
    }

    @Test
    void anIdenticalScoreIsReportedAsUndetermined() {
        Map<String, Object> alternative = at(Plan.build(Map.of(),
                withAlternative(List.of(0L, 1L, 0L, 0L, 1000000L))), "alternatives", 0);
        assertNull(at(alternative, "facts", "first_difference"));
        assertTrue(((String) at(alternative, "presentation", "summary")).contains("does not record why"));
    }

    @Test
    void scoreVectorsOfDifferentLengthAreRefused() {
        Map<String, Object> result = result(Map.of("alternatives", List.of(Map.of("score", List.of(0L, 1L)))));
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.build(Map.of(), result));
        assertTrue(error.getMessage().contains("different length"));
    }

    /** Python copies a score as the result holds it; a fraction is never truncated into another score. */
    @Test
    void aScoreIsCarriedThroughUnchanged() {
        List<Object> fractional = List.of(new BigDecimal("1.5"), 0L);
        Map<String, Object> facts = at(Plan.build(Map.of(), result(Map.of("score", fractional))), "facts");
        assertEquals(fractional, facts.get("score"));
        assertTrue(Plan.canonicalJson(Plan.build(Map.of(), result(Map.of("score", fractional))))
                .contains("\"score\":[1.5,0]"));
    }

    /** Two scores are compared only as the integers a solver records. */
    @Test
    void aScoreComparedWithAnAlternativeMustBeIntegral() {
        Map<String, Object> result = result(Map.of("score", List.of(new BigDecimal("1.5")),
                "alternatives", List.of(Map.of("score", List.of(2L)))));
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.build(Map.of(), result));
        assertTrue(error.getMessage().contains("not an integer"));
    }

    /** Ticks are read as Python's {@code int()} reads them: a boolean is 0 or 1, a whole-number string parses. */
    @Test
    void aTickIsReadAsPythonsIntReadsIt() {
        Map<String, Object> odd = placement("cube", 0, 0, 0);
        odd.put("position", Map.of("x", Map.of("ticks", true), "y", Map.of("ticks", " 7 "),
                "z", Map.of("ticks", new BigDecimal("2.9"))));
        Map<String, Object> ticks = at(Plan.build(Map.of(), result(Map.of("containers", List.of(Map.of(
                "container_type", "box", "placements", List.of(odd)))))),
                "containers", 0, "steps", 0, "placement", "position_ticks");
        assertEquals(Map.of("x", 1L, "y", 7L, "z", 2L), ticks);
        odd.put("position", Map.of("x", Map.of("ticks", "seven"), "y", Map.of("ticks", 0L), "z", Map.of("ticks", 0L)));
        assertThrows(ExecutionPlanException.class, () -> Plan.build(Map.of(), result(Map.of("containers",
                List.of(Map.of("container_type", "box", "placements", List.of(odd)))))));
    }

    @Test
    void anEmptyValueWhereAListBelongsHasNoEntries() {
        // The reference's `list(value or ())`: {}, "", 0 and false describe nothing, as null does.
        for (Object empty : List.of(Map.of(), "", 0L, false, new BigDecimal("0.0"))) {
            Map<String, Object> plan = Plan.build(Map.of(), result(Map.of("alternatives", empty, "unpacked_items", empty)));
            assertEquals(List.of(), plan.get("alternatives"));
            assertEquals(List.of(), plan.get("unplaced"));
        }
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.build(Map.of(), result(Map.of("alternatives", Map.of("a", 1L)))));
        assertTrue(error.getMessage().contains("must be a list"));
    }

    @Test
    void aResultWithNoAlternativesIsWellFormed() {
        Map<String, Object> plan = Plan.build(Map.of(), result());
        assertEquals(List.of(), plan.get("alternatives"));
        assertEquals(Plan.FORMAT, plan.get("format"));
    }

    @Test
    void anUnpackedItemKeepsItsProofLevelUnsoftened() {
        Map<String, Object> entry = at(Plan.build(Map.of(), result(Map.of("unpacked_items", List.of(Map.of(
                "item_id", "ladder#1", "item_type", "ladder", "reason", "no_container_fits",
                "details", List.of("longest dimension exceeds every container"),
                "proof", Map.of("level", "observed", "observations", List.of(Map.of("code", "too_long")))))))),
                "unplaced", 0);
        assertEquals("observed", at(entry, "facts", "proof_level"));
        // The level appears in the sentence too: a reader must not be told "cannot fit" when
        // the engine only observed that it did not.
        assertTrue(((String) at(entry, "presentation", "summary")).contains("observed"));
        assertEquals(List.of("unpacked_items[].reason", "unpacked_items[].proof.level"),
                at(entry, "presentation", "cites"));
    }

    @Test
    void everyPresentationBlockCitesAtLeastOneField() {
        Map<String, Object> plan = Plan.build(Map.of(), result(Map.of(
                "alternatives", List.of(Map.of("status", "feasible", "score", List.of(0L, 2L, 0L, 0L, 900000L))),
                "unpacked_items", List.of(Map.of("item_id", "x#1", "item_type", "x", "reason", "no_container_fits",
                        "details", List.of(), "proof", Map.of("level", "proven"))))));
        List<Object> blocks = new ArrayList<>(at(plan, "alternatives"));
        blocks.addAll(at(plan, "unplaced"));
        assertEquals(2, blocks.size());
        blocks.forEach(block -> assertFalse(((List<?>) at(block, "presentation", "cites")).isEmpty()));
    }

    @Test
    void theSameInputsProduceTheSameBytes() {
        String first = Plan.canonicalJson(Plan.build(Map.of(), result()));
        assertEquals(first, Plan.canonicalJson(Plan.build(Map.of(), result())));
        // Sorted keys and no incidental whitespace, so Python can be diffed against this.
        assertTrue(first.startsWith("{\"alternatives\":"));
        assertFalse(first.contains(", "));
    }

    @Test
    void canonicalJsonSortsMapsButNeverReordersASequence() {
        // Steps are ordered on purpose; sorting them would be a different plan.
        String json = Plan.canonicalJson(Plan.build(Map.of(), result(), Map.of(0, List.of(1, 0))));
        Object roundTrip = JsonReader.parse(json);
        assertEquals(1L, (Long) at(roundTrip, "containers", 0, "steps", 0, "sequence"));
        assertEquals(1600000L, (Long) at(roundTrip, "containers", 0, "steps", 0, "placement", "position_ticks", "x"));
    }

    @Test
    void canonicalJsonIsRfc8785() {
        // Expected string from canonical_plan_json at v1.3.0, which is RFC 8785 since then:
        // non-ASCII raw, \b and \f short, other controls as lower-case escapes, an empty
        // object as {}, a "0"-keyed object still an object, and numbers as ECMAScript writes
        // them -- 100.0 is 100, where 1.2's json.dumps spelling wrote 100.0.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("\u00e9", "\u00fc \u007f\b\f\u0001\"\\/");
        value.put("b", Map.of());
        value.put("a", Map.of("0", 1L));
        value.put("\ud83d\ude00", List.of(new BigDecimal("0.5"), new BigDecimal("1e15"), new BigDecimal("0.00001"),
                new BigDecimal("1E2"), new BigDecimal("123.456"), new BigDecimal("1e-7")));
        assertEquals("{\"a\":{\"0\":1},\"b\":{},\"\u00e9\":\"\u00fc \u007f\\b\\f\\u0001\\\"\\\\/\","
                + "\"\ud83d\ude00\":[0.5,1000000000000000,0.00001,100,123.456,1e-7]}", Plan.canonicalJson(value));
    }

    @Test
    void aResultWithoutAStatusIsNotAValidatedResult() {
        ExecutionPlanException error = assertThrows(ExecutionPlanException.class,
                () -> Plan.build(Map.of(), Map.of("containers", List.of())));
        assertTrue(error.getMessage().contains("validated result"));
    }

    @Test
    void theAdapterReferencesNoSolverNoValidatorAndNoClock() throws IOException {
        // The dependency direction from docs/EXECUTION-PLAN.md, as the cheapest test of it. The
        // compiled classes are read too, so a fully qualified use cannot slip past an import grep.
        List<String> forbidden = List.of("com.packvium.algorithm", "com.packvium.validation",
                "com.packvium.Packer", "com.packvium.serialization.RequestPacker", "java.time",
                "currentTimeMillis", "nanoTime", "java.util.Random");
        for (Path file : files(Path.of("src/main/java/com/packvium/execution"), ".java")) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            forbidden.forEach(name -> assertFalse(source.contains(name), file + " references " + name));
        }
        List<Path> classes = files(Path.of("target/classes/com/packvium/execution"), ".class");
        assertFalse(classes.isEmpty(), "no compiled execution classes found");
        for (Path file : classes) {
            String bytecode = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            forbidden.forEach(name -> assertFalse(bytecode.contains(name.replace('.', '/')),
                    file + " references " + name));
        }
    }

    private static List<Path> files(Path directory, String suffix) throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.filter(file -> file.toString().endsWith(suffix)).sorted().toList();
        }
    }
}
