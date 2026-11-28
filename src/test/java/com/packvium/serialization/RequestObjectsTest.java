package com.packvium.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.Packer;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.FlatBag;
import com.packvium.domain.Item;
import com.packvium.domain.MadeToMeasure;
import com.packvium.domain.Point;
import com.packvium.domain.RateTable;
import com.packvium.domain.Rotation;
import com.packvium.domain.Sides;
import com.packvium.support.ObjectRefusal;
import com.packvium.unit.Length;
import com.packvium.unit.Rounding;
import com.packvium.validation.FixedPlacementException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A request built from objects is refused as the same request sent as JSON is. Each case is one
 * request two ways: objects passed to {@link Packer#pack}, and JSON passed to
 * {@link RequestPacker#pack}. A value refused once the objects form a request carries the JSON
 * field and message too; one an object's own constructor refuses has no position yet, so its field
 * is empty. Cases and expectations are the reference engine's own object-path tests.
 */
// The edits address a tree built from Map<String, Object> objects.
@SuppressWarnings("unchecked")
class RequestObjectsTest {
    private static final long TOO_LARGE = 1L << 60;

    private static Map<String, Object> jsonRequest(Consumer<Map<String, Object>> edit) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("items", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", "cube",
                "dimensions", Map.of("length", "100", "width", "100", "height", "100"))))));
        request.put("containers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", "box",
                "inner_dimensions", Map.of("length", "200", "width", "100", "height", "200"))))));
        request.put("configuration", new LinkedHashMap<>(Map.of("solver_profile", "fast")));
        edit.accept(request);
        return request;
    }

    private static Item.Builder cube() {
        return Item.builder("cube", Dimensions.mm(100, 100, 100));
    }

    private static Container.Builder box() {
        return Container.builder("box", Dimensions.mm(200, 100, 200));
    }

    private static Executable packed(List<Item> items, List<Container> containers, PackingConfig config,
            List<FixedPlacement> fixed) {
        return () -> new Packer(config).pack(items, containers, fixed);
    }

    private static Executable items(Item... items) {
        return packed(List.of(items), List.of(box().build()), PackingConfig.fast(200), List.of());
    }

    private static Executable containers(Container... containers) {
        return packed(List.of(cube().build()), List.of(containers), PackingConfig.fast(200), List.of());
    }

    private static Executable configured(PackingConfig config) {
        return packed(List.of(cube().build()), List.of(box().build()), config, List.of());
    }

    private static MadeToMeasure machine(long cost) {
        return new MadeToMeasure(Sides.of(Dimensions.mm(100, 100, 100)), Sides.of(Dimensions.mm(50, 50, 50)), cost,
                0, new Sides(0, 0, 0));
    }

    private static final Map<String, Object> JSON_MACHINE = Map.of(
            "minimum", Map.of("length", "100", "width", "100", "height", "100"),
            "increment", Map.of("length", "50", "width", "50", "height", "50"));

    private static Consumer<Map<String, Object>> item(String name, Object value) {
        return request -> ((List<Map<String, Object>>) request.get("items")).get(0).put(name, value);
    }

    private static Consumer<Map<String, Object>> container(String name, Object value) {
        return request -> ((List<Map<String, Object>>) request.get("containers")).get(0).put(name, value);
    }

    private static Consumer<Map<String, Object>> configuration(String name, Object value) {
        return request -> ((Map<String, Object>) request.get("configuration")).put(name, value);
    }

    private static Consumer<Map<String, Object>> repeated(String key) {
        return request -> {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) request.get(key);
            entries.add(new LinkedHashMap<>(entries.get(0)));
        };
    }

    static Stream<Arguments> inARequest() {
        Map<String, Object> instanceZero = new LinkedHashMap<>(Map.of("item_type", "cube", "container_type", "box",
                "container_instance", 0L, "position", Map.of("x", "0", "y", "0", "z", "0"), "orientation", "LWH"));
        return Stream.of(
                Arguments.of(items(cube().quantity(TOO_LARGE).build()), item("quantity", TOO_LARGE)),
                Arguments.of(items(cube().build(), cube().build()), repeated("items")),
                Arguments.of(containers(box().build(), box().build()), repeated("containers")),
                Arguments.of(items(cube().stackingGroup("").build()), item("stacking_group", "")),
                Arguments.of(items(cube().maxStackedItems(TOO_LARGE).build()), item("max_stacked_items", TOO_LARGE)),
                Arguments.of(containers(box().costMinor(TOO_LARGE).build()), container("cost_minor", TOO_LARGE)),
                Arguments.of(containers(box().tagLimits(Map.of("fragile", TOO_LARGE)).build()),
                        container("tag_limits", Map.of("fragile", TOO_LARGE))),
                Arguments.of(containers(box().rateTable(new RateTable(List.of(1000L), List.of(TOO_LARGE))).build()),
                        container("rate_table", Map.of("weight_brackets_g", List.of(1000L),
                                "prices_minor", List.of(TOO_LARGE)))),
                Arguments.of(configured(PackingConfig.builder(SolverProfile.FAST).timeLimitMs(TOO_LARGE).build()),
                        configuration("time_limit_ms", TOO_LARGE)),
                Arguments.of(configured(PackingConfig.builder(SolverProfile.FAST).effortBudget(new EffortBudget(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(TOO_LARGE))).build()),
                        configuration("effort_budget", Map.of("max_restarts", TOO_LARGE))),
                Arguments.of(containers(box().madeToMeasure(machine(0)).outerDimensions(Dimensions.mm(210, 110, 210))
                        .build()), container("made_to_measure", JSON_MACHINE).andThen(container("outer_dimensions",
                                Map.of("length", "210", "width", "110", "height", "210")))),
                Arguments.of(containers(box().madeToMeasure(machine(1_000_000_000_000_000_000L)).build()),
                        container("made_to_measure", withCost(1_000_000_000_000_000_000L))),
                Arguments.of(containers(box().deformableBoundary(FlatBag.KIND).madeToMeasure(machine(0)).build()),
                        container("deformable_boundary", Map.of("kind", FlatBag.KIND))
                                .andThen(container("made_to_measure", JSON_MACHINE))),
                Arguments.of(packed(List.of(cube().build()), List.of(box().build()), PackingConfig.fast(200),
                        List.of(new FixedPlacement("cube", "box", Point.ORIGIN, Rotation.LWH, 0))),
                        (Consumer<Map<String, Object>>) request -> request.put("fixed_placements",
                                List.of(instanceZero))));
    }

    private static Map<String, Object> withCost(long cost) {
        Map<String, Object> machine = new LinkedHashMap<>(JSON_MACHINE);
        machine.put("cost_per_square_metre_minor", cost);
        return machine;
    }

    @ParameterizedTest
    @MethodSource("inARequest")
    void aRequestOfObjectsIsRefusedWithTheJsonReasonAndField(Executable build, Consumer<Map<String, Object>> edit) {
        InvalidRequestException objects = assertThrows(InvalidRequestException.class, build);
        InvalidRequestException json = assertThrows(InvalidRequestException.class,
                () -> RequestPacker.pack(jsonRequest(edit)));
        assertEquals(List.of(json.errorCode(), json.reason(), json.field(), json.getMessage()),
                List.of(objects.errorCode(), objects.reason(), objects.field(), objects.getMessage()));
    }

    static Stream<Arguments> onItsOwn() {
        return Stream.of(
                Arguments.of((Executable) () -> cube().quantity(0).build(), item("quantity", 0L)),
                Arguments.of((Executable) () -> Dimensions.mm("-1", "100", "100"),
                        (Consumer<Map<String, Object>>) request -> item("dimensions",
                                Map.of("length", "-1", "width", "100", "height", "100")).accept(request)),
                Arguments.of((Executable) () -> Length.of(1, "furlong", Rounding.NEAREST),
                        (Consumer<Map<String, Object>>) request -> request.put("units", Map.of("length", "furlong"))),
                Arguments.of((Executable) () -> cube().minimumSupportRatio(2).build(),
                        item("minimum_support_ratio", 2L)),
                Arguments.of((Executable) () -> box().quantity(0L).build(), container("quantity", 0L)),
                Arguments.of((Executable) () -> PackingConfig.builder(SolverProfile.FAST).maxCandidatePoints(8).build(),
                        configuration("max_candidate_points", 8L)),
                Arguments.of((Executable) () -> new EffortBudget(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(0L)), configuration("effort_budget", Map.of("max_restarts", 0L))));
    }

    @ParameterizedTest
    @MethodSource("onItsOwn")
    void anObjectRefusesWithTheJsonReasonAndNoField(Executable build, Consumer<Map<String, Object>> edit) {
        ObjectRefusal objects = assertThrows(ObjectRefusal.class, build);
        InvalidRequestException json = assertThrows(InvalidRequestException.class,
                () -> RequestPacker.pack(jsonRequest(edit)));
        assertEquals(List.of(json.errorCode(), json.reason(), ""),
                List.of(objects.errorCode(), objects.reason(), objects.field()));
    }

    @Test
    void aQuantityPastTheExactRangeIsRefusedBeforeAnySolve() {
        InvalidRequestException error = assertThrows(InvalidRequestException.class,
                items(cube().quantity(TOO_LARGE).build()));
        assertEquals(List.of("above_maximum", "/items/0/quantity"), List.of(error.reason(), error.field()));
    }

    @Test
    void aFixedPlacementCountsContainerInstancesFromOne() {
        FixedPlacementException error = assertThrows(FixedPlacementException.class, packed(List.of(cube().build()),
                List.of(box().build()), PackingConfig.fast(200),
                List.of(new FixedPlacement("cube", "box", Point.ORIGIN, Rotation.LWH, 0))));
        assertEquals(List.of("malformed", "/fixed_placements/0/container_instance",
                "invalid_fixed_placement: fixed_placements[0].container_instance counts from 1"),
                List.of(error.reason(), error.field(), error.getMessage()));
    }

    @Test
    void acceptedStackingGroupsAreCheckedInCodePointOrder() {
        InvalidRequestException error = assertThrows(InvalidRequestException.class,
                items(cube().acceptsStackingGroups(List.of("pallet", "", "box")).build()));
        assertEquals(List.of("invalid_value", "/items/0/accepts_stacking_groups/0"),
                List.of(error.reason(), error.field()));
    }

    @Test
    void aConstructorRefusalKeepsItsOwnMessage() {
        ObjectRefusal error = assertThrows(ObjectRefusal.class, () -> cube().quantity(0).build());
        assertEquals("item quantity must be positive", error.getMessage());
    }

    @Test
    void theJsonPathStillReportsWhatItsRulesDoNotNameAsInvalidValue() {
        InvalidRequestException error = assertThrows(InvalidRequestException.class,
                () -> RequestPacker.pack(jsonRequest(container("rate_table",
                        Map.of("weight_brackets_g", List.of(5000L, 1000L), "prices_minor", List.of(500L, 900L))))));
        assertEquals(List.of("invalid_value", "", "invalid_request: rate_table weight brackets must be strictly ascending"),
                List.of(error.reason(), error.field(), error.getMessage()));
    }
}
