package com.packvium.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.algorithm.UnknownSolverException;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.support.RequestRules;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import com.packvium.validation.FixedPlacementException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Structured request errors: one type, a closed reason, and the pointer of the bad value. Cases and
 * expected values are Python's {@code test_request_errors.py} at tag v1.4.0; the full cross-engine
 * list is the request-error conformance run.
 */
// The edits address a tree `mutable` built, whose every object is a Map<String, Object>.
@SuppressWarnings("unchecked")
class RequestErrorsTest {
    private static final List<String> REASONS = List.of("missing_field", "wrong_type", "below_minimum",
            "above_maximum", "negative_measure", "invalid_unit", "duplicate_id", "not_allowed", "invalid_value");

    private static Map<String, Object> request() {
        return mutable(JsonReader.parse("{\"items\":[{\"id\":\"cube\",\"quantity\":2,\"weight\":\"1000\","
                + "\"dimensions\":{\"length\":\"100\",\"width\":\"100\",\"height\":\"100\"}}],"
                + "\"containers\":[{\"id\":\"box\",\"quantity\":2,"
                + "\"inner_dimensions\":{\"length\":\"200\",\"width\":\"100\",\"height\":\"200\"}}],"
                + "\"configuration\":{\"solver_profile\":\"fast\"}}"));
    }

    private static InvalidRequestException refusal(Consumer<Map<String, Object>> edit) {
        Map<String, Object> data = request();
        edit.accept(data);
        return assertThrows(InvalidRequestException.class, () -> RequestPacker.pack(data));
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of(item("quantity", 0L), "below_minimum", "/items/0/quantity", "must be at least 1"),
                Arguments.of(item("quantity", "2"), "wrong_type", "/items/0/quantity", "must be an integer"),
                Arguments.of(item("quantity", BigInteger.TWO.pow(53)), "above_maximum", "/items/0/quantity",
                        "must be at most 9007199254740991"),
                Arguments.of(item("quantity", BigInteger.TWO.pow(60).negate()), "below_minimum", "/items/0/quantity",
                        "must be at least 1"),
                Arguments.of(side("width", "-1"), "negative_measure", "/items/0/dimensions/width", "cannot be negative"),
                Arguments.of(side("width", Map.of("value", "1", "unit", "furlong")), "invalid_unit",
                        "/items/0/dimensions/width", "has an unknown unit \"furlong\""),
                Arguments.of(side("width", Map.of("unit", "mm")), "wrong_type", "/items/0/dimensions/width",
                        "must be a measure"),
                Arguments.of(side("width", new BigDecimal("1.5")), "wrong_type", "/items/0/dimensions/width",
                        "must be a measure"),
                Arguments.of((Consumer<Map<String, Object>>) data -> dimensions(data).remove("height"), "missing_field",
                        "/items/0/dimensions/height", "is required"),
                Arguments.of((Consumer<Map<String, Object>>) data -> items(data).add(mutable(items(data).get(0))),
                        "duplicate_id", "/items/1/id", "repeats the id \"cube\""),
                Arguments.of(item("minimum_support_ratio", 2L), "above_maximum", "/items/0/minimum_support_ratio",
                        "must be at most 1"),
                Arguments.of(item("minimum_support_ratio", "x"), "wrong_type", "/items/0/minimum_support_ratio",
                        "must be a number"),
                Arguments.of(item("compression_ratio", -1L), "below_minimum", "/items/0/compression_ratio",
                        "must be at least 0"),
                Arguments.of(container("tag_limits", Map.of("a/b~c", 0L)), "below_minimum",
                        "/containers/0/tag_limits/a~1b~0c", "must be at least 1"),
                Arguments.of(container("tag_limits", Map.of("b", 0L, "10", 0L)), "below_minimum",
                        "/containers/0/tag_limits/10", "must be at least 1"),
                Arguments.of(container("rate_table", Map.of("prices_minor", List.of(1L, -2L))), "below_minimum",
                        "/containers/0/rate_table/prices_minor/1", "must be at least 0"),
                Arguments.of(container("obstacles", List.of(Map.of("id", "o", "origin", Map.of("z", "-1"),
                        "dimensions", Map.of("length", "1", "width", "1", "height", "1")))), "negative_measure",
                        "/containers/0/obstacles/0/origin/z", "cannot be negative"),
                Arguments.of(top("units", Map.of("length", 5L)), "invalid_unit", "/units/length",
                        "has an unknown unit 5"),
                Arguments.of(top("items", Map.of("cube", 1L)), "wrong_type", "/items", "must be a list"),
                Arguments.of((Consumer<Map<String, Object>>) data -> ((List<Object>) data.get("items")).add("slab"), "wrong_type", "/items/1",
                        "must be an object"),
                Arguments.of(item("id", 7L), "wrong_type", "/items/0/id", "must be a string"),
                Arguments.of(side("width", "ten"), "wrong_type", "/items/0/dimensions/width", "must be a measure"),
                Arguments.of(side("width", Map.of("value", List.of(1L))), "wrong_type", "/items/0/dimensions/width",
                        "must be a measure"),
                Arguments.of(side("width", Map.of("value", "1", "unit", 3L)), "invalid_unit",
                        "/items/0/dimensions/width", "has an unknown unit 3"),
                Arguments.of(container("rate_table", Map.of("weight_brackets_g", 5L)), "wrong_type",
                        "/containers/0/rate_table/weight_brackets_g", "must be a list"),
                Arguments.of(container("obstacles", List.of(Map.of("id", "o",
                        "dimensions", Map.of("length", "1", "width", "1")))), "missing_field",
                        "/containers/0/obstacles/0/dimensions/height", "is required"),
                Arguments.of(configuration("solver_profile", "fastest"), "not_allowed", "/configuration/solver_profile",
                        "must be one of [\"fast\",\"balanced\",\"quality\",\"exact_small\"]"),
                Arguments.of(configuration("effort_budget", Map.of("max_restarts", 0L)), "below_minimum",
                        "/configuration/effort_budget/max_restarts", "must be at least 1"),
                Arguments.of(configuration("max_containers", -1L), "below_minimum", "/configuration/max_containers",
                        "must be at least 1"),
                // 1.5: the schema closes `configuration` and `effort_budget`, and names every
                // objective and access direction.
                Arguments.of(configuration("top_k", 2L).andThen(configuration("profile", "balanced")), "not_allowed",
                        "/configuration/profile", "is not a known field"),
                Arguments.of(configuration("effort_budget", Map.of("max_nodes", 1L)), "not_allowed",
                        "/configuration/effort_budget/max_nodes", "is not a known field"),
                Arguments.of(configuration("parallel", true), "not_allowed", "/configuration/parallel",
                        "is not a known field"),
                Arguments.of(configuration("objective", "cheapest"), "not_allowed", "/configuration/objective",
                        "must be one of [\"default\",\"lowest_cost\",\"shipping_cost\",\"lowest_landed_cost\","
                                + "\"open_dimension_height\",\"maximum_value\",\"maximum_volume\"]"),
                Arguments.of(container("access_directions", List.of("upwards")), "not_allowed",
                        "/containers/0/access_directions/0", "must be one of [\"+x\",\"-x\",\"+y\",\"-y\",\"+z\",\"-z\"]"),
                Arguments.of(container("access_directions", "+x"), "wrong_type", "/containers/0/access_directions",
                        "must be a list"),
                Arguments.of(side("width", Map.of("value", new BigDecimal("0.5"))), "wrong_type",
                        "/items/0/dimensions/width", "must be a measure"),
                Arguments.of(container("access_directions", Arrays.asList("+x", null)), "not_allowed",
                        "/containers/0/access_directions/1",
                        "must be one of [\"+x\",\"-x\",\"+y\",\"-y\",\"+z\",\"-z\"]"));
    }

    /** Refusals the model raises after the rules, spelt as the reference spells them. */
    static Stream<Arguments> modelRefusals() {
        Map<String, Object> pillar = Map.of("id", "p", "origin", Map.of("x", "190"),
                "dimensions", Map.of("length", "10", "width", "10", "height", "10"));
        Map<String, Object> noId = new LinkedHashMap<>(pillar);
        noId.remove("id");
        Map<String, Object> nullAxis = new LinkedHashMap<>(pillar);
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("x", null);
        nullAxis.put("origin", origin);
        Map<String, Object> nullUnit = new LinkedHashMap<>();
        nullUnit.put("length", null);
        return Stream.of(
                Arguments.of(container("obstacles", List.of(noId)), "'id'"),
                Arguments.of(container("obstacles", List.of(nullAxis)), "[<class 'decimal.ConversionSyntax'>]"),
                Arguments.of(container("rate_table", Map.of("prices_minor", List.of(500L))), "'weight_brackets_g'"),
                Arguments.of(container("rate_table", Map.of("weight_brackets_g", List.of(1000L))), "'prices_minor'"),
                Arguments.of(item("tags", List.of(List.of())), "unhashable type: 'list'"),
                Arguments.of(item("tags", List.of(Map.of("k", 1L))), "unhashable type: 'dict'"),
                Arguments.of(top("units", nullUnit), "unsupported length unit: None"),
                Arguments.of(item("id", ""), "item id is required"),
                Arguments.of(side("width", "0"), "all dimensions must be positive"),
                Arguments.of(container("id", ""), "container id is required"),
                Arguments.of(container("inner_dimensions", Map.of("length", "1", "width", "100", "height", "200"))
                        .andThen(container("obstacles", List.of(pillar))), "obstacle p lies outside container"),
                // The seed is read with int() and the solver list with tuple().
                Arguments.of(configuration("seed", "x"), "invalid literal for int() with base 10: 'x'"),
                Arguments.of(configuration("seed", " 1 cm"), "invalid literal for int() with base 10: ' 1 cm'"),
                Arguments.of(configuration("seed", List.of(1L)),
                        "int() argument must be a string, a bytes-like object or a real number, not 'list'"),
                Arguments.of(configuration("seed", Map.of("value", "1")),
                        "int() argument must be a string, a bytes-like object or a real number, not 'dict'"),
                Arguments.of(configuration("solvers", 1L), "'int' object is not iterable"),
                Arguments.of(configuration("solvers", true), "'bool' object is not iterable"));
    }

    /** What {@code int()} and {@code tuple()} accept is answered, as the reference answers it. */
    static Stream<Consumer<Map<String, Object>>> coercedConfiguration() {
        return Stream.of(configuration("seed", true), configuration("seed", false),
                configuration("seed", new BigDecimal("1.5")), configuration("seed", " -1 "),
                configuration("seed", "1_000"), configuration("solvers", Map.of()));
    }

    @Test
    void aSolverStringIsIteratedIntoUnknownNamesAsInPython() {
        Map<String, Object> data = request();
        configuration("solvers", "x").accept(data);
        assertEquals("unknown solver name(s) ['x']; expected one of ['exact_small', 'extreme_points', 'grid', "
                + "'homogeneous_blocks', 'layer', 'maximal_spaces']",
                assertThrows(UnknownSolverException.class, () -> RequestPacker.pack(data)).getMessage());
    }

    @ParameterizedTest
    @MethodSource("coercedConfiguration")
    void aSeedOrSolverListPythonCoercesIsAnswered(Consumer<Map<String, Object>> edit) {
        Map<String, Object> data = request();
        edit.accept(data);
        assertTrue(RequestPacker.pack(data).containsKey("status"));
    }

    @ParameterizedTest
    @MethodSource("modelRefusals")
    void whatOnlyTheModelRefusesReadsAsTheReferenceReadsIt(Consumer<Map<String, Object>> edit, String detail) {
        InvalidRequestException error = refusal(edit);
        assertEquals(List.of("invalid_value", "", "invalid_request: " + detail),
                List.of(error.reason(), error.field(), error.getMessage()));
    }

    @Test
    void anObstacleWhoseIdIsNullIsAnswered() {
        Map<String, Object> obstacle = new LinkedHashMap<>(Map.of("origin", Map.of("x", "190"),
                "dimensions", Map.of("length", "10", "width", "10", "height", "10")));
        obstacle.put("id", null);
        Map<String, Object> data = request();
        list(data, "containers").get(0).put("obstacles", List.of(obstacle));
        assertTrue(RequestPacker.pack(data).containsKey("status"));
    }

    /** A negative measure is refused for its sign, however far past the 64-bit tick range it is. */
    static Stream<Arguments> farNegative() {
        Object huge = BigInteger.TWO.pow(60).negate().longValueExact();
        return Stream.of(
                Arguments.of(item("weight", huge), "/items/0/weight"),
                Arguments.of(side("length", huge), "/items/0/dimensions/length"),
                Arguments.of(configuration("clearance", huge), "/configuration/clearance"),
                Arguments.of(container("max_payload", huge), "/containers/0/max_payload"),
                Arguments.of(container("max_payload", BigInteger.TWO.pow(70).negate()), "/containers/0/max_payload"),
                Arguments.of(container("obstacles", List.of(Map.of("id", "o", "origin", Map.of("x", huge),
                        "dimensions", Map.of("length", "1", "width", "1", "height", "1")))),
                        "/containers/0/obstacles/0/origin/x"));
    }

    @ParameterizedTest
    @MethodSource("farNegative")
    void aFarNegativeMeasureIsNegative(Consumer<Map<String, Object>> edit, String field) {
        InvalidRequestException error = refusal(edit);
        assertEquals(List.of("negative_measure", field, "cannot be negative"),
                List.of(error.reason(), error.field(), error.detail()));
    }

    @Test
    void aFarNegativeFixedCoordinateIsNegative() {
        InvalidRequestException error = refusal(top("fixed_placements", List.of(Map.of("item_type", "cube",
                "container_type", "box", "orientation", "LWH",
                "position", Map.of("x", BigInteger.TWO.pow(60).negate().longValueExact())))));
        assertEquals("invalid_fixed_placement: fixed_placements[0].position.x cannot be negative", error.getMessage());
        assertEquals("malformed", error.reason());
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void aMalformedRequestNamesTheValueAndTheRule(Consumer<Map<String, Object>> edit, String reason, String field,
            String detail) {
        InvalidRequestException error = refusal(edit);
        assertEquals(List.of("invalid_request", reason, field, detail),
                List.of(error.errorCode(), error.reason(), error.field(), error.detail()));
        assertEquals("invalid_request: " + field + ": " + detail, error.getMessage());
        assertTrue(REASONS.contains(error.reason()));
    }

    @Test
    void theErrorIsStillAnIllegalArgumentExceptionForExistingCallers() {
        assertTrue(refusal(item("quantity", 0L)) instanceof IllegalArgumentException);
    }

    @Test
    void aRequestThatIsNotAnObjectIsRefusedAsAWhole() {
        InvalidRequestException error = assertThrows(InvalidRequestException.class,
                () -> RequestPacker.pack(List.of(request())));
        assertEquals(List.of("", "invalid_request: must be an object"), List.of(error.field(), error.getMessage()));
    }

    @Test
    void whatTheRulesDoNotNameStillArrivesAsARequestError() {
        InvalidRequestException error = refusal(item("shape_type", "blob"));
        assertEquals(List.of("invalid_request", "invalid_value", ""),
                List.of(error.errorCode(), error.reason(), error.field()));
    }

    @Test
    void aFixedPlacementRefusalIsARequestErrorWithItsOwnCode() {
        InvalidRequestException error = refusal(top("fixed_placements", List.of(Map.of("item_type", "cube",
                "container_type", "box", "orientation", "LWH", "position", Map.of("x", "-5")))));
        assertTrue(error instanceof FixedPlacementException);
        assertEquals(List.of("invalid_fixed_placement", "malformed", "/fixed_placements/0/position/x"),
                List.of(error.errorCode(), error.reason(), error.field()));
        assertEquals("invalid_fixed_placement: fixed_placements[0].position.x cannot be negative", error.getMessage());
    }

    @Test
    void aFixedSetThatCannotHoldIsReportedAgainstTheWholeList() {
        Map<String, Object> entry = Map.of("item_type", "cube", "container_type", "box", "orientation", "LWH",
                "position", Map.of("x", "0"));
        InvalidRequestException error = refusal(top("fixed_placements", List.of(entry, entry)));
        assertEquals(List.of("cannot_hold", "/fixed_placements"), List.of(error.reason(), error.field()));
    }

    @Test
    void nullOptionalsAreAbsentAndAValidRequestPasses() {
        Map<String, Object> data = request();
        items(data).get(0).put("max_stacked_items", null);
        items(data).get(0).put("stop_index", null);
        data.put("units", null);
        ((Map<String, Object>) data.get("configuration")).put("effort_budget", null);
        RequestRules.check(data);
        data.put("units", new LinkedHashMap<>());
        RequestRules.check(data);
    }

    @Test
    void aPointerEscapesTildeAndSlash() {
        assertEquals("/containers/0/tag_limits/a~1b~0c", RequestRules.pointer("/containers/0/tag_limits", "a/b~c"));
    }

    @Test
    void anIntegralFloatIsAWholeValueAndAFractionIsRefused() {
        assertEquals(Length.parse(100L).ticks(), Length.parse(new BigDecimal("100.0")).ticks());
        assertEquals(Weight.parse(5L).ticks(), Weight.parse(new BigDecimal("5.0")).ticks());
        assertThrows(IllegalArgumentException.class, () -> Length.parse(new BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class, () -> Weight.parse(new BigDecimal("1.5")));
        Map<String, Object> data = request();
        items(data).get(0).put("quantity", new BigDecimal("2.0"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) ((List<?>) RequestPacker.pack(data).get("containers")).get(0))
                .get("placements")).size());
    }

    @Test
    void theTypedApiKeepsItsOwnGuards() {
        assertEquals("max_containers must be at least 1", assertThrows(IllegalArgumentException.class,
                () -> PackingConfig.builder(SolverProfile.BALANCED).maxContainers(0L).build()).getMessage());
        assertEquals("effort_budget.max_search_nodes must be at least 1", assertThrows(IllegalArgumentException.class,
                () -> new EffortBudget(Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty()))
                .getMessage());
    }

    // ------------------------------------------------------------------------------ edits

    private static Consumer<Map<String, Object>> top(String name, Object value) {
        return data -> data.put(name, value);
    }

    private static Consumer<Map<String, Object>> item(String name, Object value) {
        return data -> items(data).get(0).put(name, value);
    }

    private static Consumer<Map<String, Object>> side(String name, Object value) {
        return data -> dimensions(data).put(name, value);
    }

    private static Consumer<Map<String, Object>> container(String name, Object value) {
        return data -> list(data, "containers").get(0).put(name, value);
    }

    private static Consumer<Map<String, Object>> configuration(String name, Object value) {
        return data -> ((Map<String, Object>) data.get("configuration")).put(name, value);
    }

    private static Map<String, Object> dimensions(Map<String, Object> data) {
        return (Map<String, Object>) items(data).get(0).get("dimensions");
    }

    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return list(data, "items");
    }

    private static List<Map<String, Object>> list(Map<String, Object> data, String name) {
        return (List<Map<String, Object>>) data.get(name);
    }

    /** A deep, mutable copy, every object a {@code Map<String, Object>}. */
    private static <T> T mutable(Object tree) {
        if (tree instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, mutable(member)));
            return (T) copy;
        }
        if (tree instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>();
            elements.forEach(element -> copy.add(mutable(element)));
            return (T) copy;
        }
        return (T) tree;
    }
}
