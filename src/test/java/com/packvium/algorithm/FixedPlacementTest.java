package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.result.PackingResult;
import com.packvium.serialization.InvalidRequestException;
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.RequestPacker;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import com.packvium.validation.FixedPlacementException;
import com.packvium.validation.IndependentSolutionValidator;
import com.packvium.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Fixed placements: admission, seeding and the validator rule. Cases and expected values are
 * Python's {@code test_fixed_placements.py} at tag v1.4.0, which PHP's {@code FixedPlacementTest}
 * mirrors.
 */
class FixedPlacementTest {
    private static final String CUBE = "{\"id\":\"cube\",\"quantity\":%d,\"weight\":\"1000\","
            + "\"dimensions\":{\"length\":\"100\",\"width\":\"100\",\"height\":\"100\"}%s}";
    private static final String BOX = "{\"id\":\"box\",\"quantity\":%d,"
            + "\"inner_dimensions\":{\"length\":\"%s\",\"width\":\"100\",\"height\":\"200\"}%s}";

    private static String cube(int quantity) {
        return String.format(CUBE, quantity, "");
    }

    private static String box(int quantity) {
        return String.format(BOX, quantity, "200", "");
    }

    private static String fixed(String x, String z, int instance) {
        return "{\"item_type\":\"cube\",\"container_type\":\"box\",\"container_instance\":" + instance
                + ",\"position\":{\"x\":\"" + x + "\",\"y\":\"0\",\"z\":\"" + z + "\"},\"orientation\":\"LWH\"}";
    }

    private static String request(String items, String containers, String placements, String configuration) {
        return "{\"items\":[" + items + "],\"containers\":[" + containers + "],\"fixed_placements\":[" + placements
                + "]" + (configuration.isEmpty() ? "" : ",\"configuration\":" + configuration) + "}";
    }

    private static Map<String, Object> pack(String json) {
        return RequestPacker.pack(JsonReader.parse(json));
    }

    /** (container id, item id, x, y, z) of every placement reported fixed, in result order. */
    private static List<String> fixedRows(Map<String, Object> result) {
        List<String> rows = new ArrayList<>();
        for (Object container : (List<?>) result.get("containers")) {
            Map<?, ?> packed = (Map<?, ?>) container;
            for (Object entry : (List<?>) packed.get("placements")) {
                Map<?, ?> placement = (Map<?, ?>) entry;
                if (Boolean.TRUE.equals(placement.get("fixed"))) {
                    Map<?, ?> position = (Map<?, ?>) placement.get("position");
                    rows.add(packed.get("id") + " " + placement.get("item_id") + " " + value(position, "x") + " "
                            + value(position, "y") + " " + value(position, "z"));
                }
            }
        }
        return rows;
    }

    private static Object value(Map<?, ?> scalars, String axis) {
        return ((Map<?, ?>) scalars.get(axis)).get("value");
    }

    private static int placed(Map<String, Object> result) {
        return ((List<?>) result.get("containers")).stream()
                .mapToInt(container -> ((List<?>) ((Map<?, ?>) container).get("placements")).size()).sum();
    }

    private static String refusal(String json) {
        return assertThrows(FixedPlacementException.class, () -> pack(json)).getMessage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fast", "balanced", "quality"})
    void aFixedItemIsReportedWhereTheRequestPutIt(String profile) {
        Map<String, Object> result = pack(request(cube(5), box(3), fixed("100", "0", 1),
                "{\"solver_profile\":\"" + profile + "\"}"));
        assertTrue(Set.of("feasible", "optimal").contains(result.get("status")), String.valueOf(result.get("status")));
        assertEquals(List.of("box#1 cube#1 100 0 0"), fixedRows(result));
        assertEquals(5, placed(result));
    }

    @Test
    void onlyFixedPlacementsCarryTheFlag() {
        Map<String, Object> result = pack(request(cube(5), box(3), fixed("100", "0", 1), ""));
        long flagged = ((List<?>) result.get("containers")).stream()
                .flatMap(container -> ((List<?>) ((Map<?, ?>) container).get("placements")).stream())
                .filter(placement -> ((Map<?, ?>) placement).containsKey("fixed")).count();
        assertEquals(1, flagged);
    }

    @Test
    void fixedItemsTakeTheFirstInstancesOfTheirTypeInListedOrder() {
        Map<String, Object> result = pack(request(cube(5), box(3),
                fixed("100", "0", 1) + "," + fixed("0", "0", 1), ""));
        assertEquals(List.of("box#1 cube#1 100 0 0", "box#1 cube#2 0 0 0"), fixedRows(result));
    }

    @Test
    void fixedContainersOpenFirstAndFreeOnesAreNumberedAfterThem() {
        Map<String, Object> result = pack(request(cube(9), box(3),
                fixed("0", "0", 1) + "," + fixed("0", "0", 2), ""));
        List<Object> ids = ((List<?>) result.get("containers")).stream()
                .map(container -> ((Map<?, ?>) container).get("id")).collect(Collectors.toList());
        assertEquals(List.of("box#1", "box#2", "box#3"), ids);
    }

    @Test
    void aFixedContainerIsKeptWhenNoFreeItemFitsInIt() {
        String tray = "{\"id\":\"tray\",\"quantity\":1,\"inner_dimensions\":{\"length\":\"100\",\"width\":\"100\","
                + "\"height\":\"100\"}}";
        String placement = fixed("0", "0", 1).replace("\"container_type\":\"box\"", "\"container_type\":\"tray\"");
        Map<String, Object> result = pack(request(cube(3), tray + "," + box(3), placement, ""));
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("containers")).get(0);
        assertEquals("tray#1", first.get("id"));
        assertEquals(1, ((List<?>) first.get("placements")).size());
    }

    @Test
    void aFixedContainerSurvivesASearchThatRunsOutOfEffort() {
        Map<String, Object> result = pack(request(cube(40), box(20), fixed("0", "0", 1) + "," + fixed("0", "0", 2),
                "{\"effort_budget\":{\"max_search_nodes\":1}}"));
        assertEquals(Set.of("box#1", "box#2"),
                fixedRows(result).stream().map(row -> row.split(" ")[0]).collect(Collectors.toSet()));
        assertTrue(((List<?>) result.get("warnings")).isEmpty(), String.valueOf(result.get("warnings")));
    }

    @Test
    void aFreeItemRestingOnAFixedOneLoadsIt() {
        String narrow = String.format(BOX, 1, "100", "");
        Map<String, Object> result = pack(request(cube(2), narrow, fixed("0", "0", 1),
                "{\"solvers\":[\"extreme_points\"]}"));
        Map<?, ?> container = (Map<?, ?>) ((List<?>) result.get("containers")).get(0);
        Map<Object, Map<?, ?>> byId = ((List<?>) container.get("placements")).stream().map(entry -> (Map<?, ?>) entry)
                .collect(Collectors.toMap(entry -> entry.get("item_id"), entry -> entry));
        assertEquals("100", value((Map<?, ?>) byId.get("cube#2").get("position"), "z"));
        assertEquals("1000", ((Map<?, ?>) byId.get("cube#1").get("top_load")).get("value"));
    }

    @Test
    void fixedWeightCountsAgainstPayload() {
        Map<String, Object> result = pack(request(cube(3), String.format(BOX, 2, "200", ",\"max_payload\":\"1000\""),
                fixed("0", "0", 1), ""));
        List<Integer> counts = ((List<?>) result.get("containers")).stream()
                .map(container -> ((List<?>) ((Map<?, ?>) container).get("placements")).size()).toList();
        assertEquals(List.of(1, 1), counts);
        assertEquals(1, ((List<?>) result.get("unpacked_items")).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"grid", "homogeneous_blocks", "maximal_spaces", "layer"})
    void everySolverPacksAroundFixedItems(String solver) {
        Map<String, Object> result = pack(request(cube(5), box(3), fixed("100", "0", 1),
                "{\"solvers\":[\"" + solver + "\"]}"));
        assertEquals(List.of("box#1 cube#1 100 0 0"), fixedRows(result));
        assertTrue(((List<?>) result.get("warnings")).isEmpty(), String.valueOf(result.get("warnings")));
        assertEquals(5, placed(result));
    }

    @Test
    void theLatticeStillServesContainersWithoutFixedItems() {
        Map<String, Object> result = pack(request(cube(12), box(4), fixed("100", "0", 1),
                "{\"solver_profile\":\"fast\",\"require_placement_coordinates\":false}"));
        List<?> containers = (List<?>) result.get("containers");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) ((Map<?, ?>) containers.get(0)).get("placements")).get(0);
        assertEquals(Boolean.TRUE, first.get("fixed"));
        assertTrue(containers.subList(1, containers.size()).stream()
                .anyMatch(container -> ((Map<?, ?>) container).containsKey("lattice_summary")));
        assertTrue(((List<?>) result.get("warnings")).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F100,F100]}|collision",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[CRATEITEM]}|unknown item type \"crate\"",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[CRATEBOX]}|unknown container type \"crate\"",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F100I2]}|box instances [2] are not numbered 1..1",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F150]}|outside_container: cube#1",
        "{\"items\":[CUBE1],\"containers\":[BOX3],\"fixed_placements\":[F100,F0]}|2 cube fixed, 1 requested",
        "{\"items\":[UPRIGHT],\"containers\":[BOX3],\"fixed_placements\":[HWL]}|cube may not be placed in orientation HWL",
        "{\"items\":[CUBE5],\"containers\":[BOX3PAYLOAD],\"fixed_placements\":[F100]}|payload_exceeded",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F100],\"configuration\":{\"clearance\":\"1\"}}"
                + "|outside_container",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F100Z50],"
                + "\"configuration\":{\"minimum_support_ratio\":1}}|insufficient_support",
        "{\"items\":[CUBE5],\"containers\":[BOX1],\"fixed_placements\":[F100,F0I2]}|2 box named, 1 available",
        "{\"items\":[CUBE5],\"containers\":[BOX3],\"fixed_placements\":[F100,F0I2],"
                + "\"configuration\":{\"max_containers\":1}}|2 containers hold fixed items, max_containers is 1",
    })
    void aFixedSetThatCannotHoldIsRefusedBeforeSearch(String template, String fragment) {
        String json = template.replace("CUBE5", cube(5)).replace("CUBE1", cube(1))
                .replace("UPRIGHT", String.format(CUBE, 5, ",\"keep_upright\":true"))
                .replace("BOX3PAYLOAD", String.format(BOX, 3, "200", ",\"max_payload\":\"500\""))
                .replace("BOX3", box(3)).replace("BOX1", box(1))
                .replace("F100I2", fixed("100", "0", 2)).replace("F100Z50", fixed("100", "50", 1))
                .replace("F100", fixed("100", "0", 1)).replace("F150", fixed("150", "0", 1))
                .replace("F0I2", fixed("0", "0", 2)).replace("F0", fixed("0", "0", 1))
                .replace("CRATEITEM", fixed("0", "0", 1).replace("\"item_type\":\"cube\"", "\"item_type\":\"crate\""))
                .replace("CRATEBOX", fixed("0", "0", 1).replace("\"container_type\":\"box\"",
                        "\"container_type\":\"crate\""))
                .replace("HWL", fixed("0", "0", 1).replace("LWH", "HWL"));
        String message = refusal(json);
        assertTrue(message.startsWith("invalid_fixed_placement: "), message);
        assertTrue(message.contains(fragment), message);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"x\"|fixed_placements is a list|/fixed_placements",
        "[5]|fixed_placements[0] is an object|/fixed_placements/0",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"note\":1}]"
                + "|fixed_placements[0] cannot carry [\"note\"]|/fixed_placements/0",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\"}]|fixed_placements[0] needs [\"orientation\"]"
                + "|/fixed_placements/0",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"container_instance\":\"1\"}]"
                + "|fixed_placements[0].container_instance counts from 1|/fixed_placements/0/container_instance",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"container_instance\":1.5}]"
                + "|fixed_placements[0].container_instance counts from 1|/fixed_placements/0/container_instance",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"position\":[\"0\"]}]"
                + "|fixed_placements[0].position is a point object|/fixed_placements/0/position",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"position\":{\"x\":true}}]"
                + "|fixed_placements[0].position.x is a measure|/fixed_placements/0/position/x",
        "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\",\"position\":{\"x\":\"-1\"}}]"
                + "|fixed_placements[0].position.x cannot be negative|/fixed_placements/0/position/x",
    })
    void aFixedPlacementThatIsNotTheSchemasShapeIsRefusedNotCoerced(String placements, String detail, String field) {
        String json = "{\"items\":[" + cube(5) + "],\"containers\":[" + box(3) + "],\"fixed_placements\":" + placements
                + "}";
        FixedPlacementException refusal = assertThrows(FixedPlacementException.class, () -> pack(json));
        assertEquals("invalid_fixed_placement: " + detail, refusal.getMessage());
        assertEquals("malformed", refusal.reason());
        assertEquals(field, refusal.field());
        assertEquals("invalid_fixed_placement", refusal.errorCode());
        assertTrue(refusal instanceof InvalidRequestException);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[{\"item_type\":\"cube\",\"container_type\":\"box\",\"orientation\":\"LWH\","
            + "\"container_instance\":1.0}]"})
    void nullFixedPlacementsAndAnIntegralFloatInstanceAreAccepted(String placements) {
        String json = "{\"items\":[" + cube(5) + "],\"containers\":[" + box(3) + "],\"fixed_placements\":" + placements
                + "}";
        assertFalse("error".equals(pack(json).get("status")));
    }

    @Test
    void anUnknownItemTypeIsQuotedAsJson() {
        String placement = fixed("0", "0", 1).replace("\"item_type\":\"cube\"", "\"item_type\":\"cr\\\"ate\"");
        assertEquals("invalid_fixed_placement: unknown item type \"cr\\\"ate\"",
                refusal(request(cube(5), box(3), placement, "")));
    }

    @Test
    void theValidatorCatchesAMovedFixedItem() {
        Item cube = Item.builder("cube", Dimensions.mm(100, 100, 100)).weight(Weight.parse("1000g")).quantity(5).build();
        Container box = Container.builder("box", Dimensions.mm(200, 100, 200)).quantity(3L).build();
        FixedPlacement wanted = new FixedPlacement("cube", "box", at(100, 0, 0), Rotation.LWH);
        PackingResult result = new Packer(PackingConfig.balanced()).pack(List.of(cube), List.of(box), List.of(wanted));
        PackingRequest request = new PackingRequest(List.of(cube), List.of(box), List.of(wanted));
        List<PackedContainer> moved = new ArrayList<>();
        for (PackedContainer packed : result.containers()) {
            List<Placement> placements = packed.placements().stream().map(placement -> placement.fixed()
                    ? new Placement(placement.instance(), at(0, 0, 100), placement.rotation(), placement.dimensions(),
                            at(0, 0, 100), placement.envelopeDimensions(), 1.0, placement.topLoad(), true)
                    : placement).toList();
            moved.add(new PackedContainer(packed.container(), packed.sequence(), placements));
        }
        Set<String> codes = new IndependentSolutionValidator().validate(request, moved).issues().stream()
                .map(ValidationIssue::code).collect(Collectors.toSet());
        assertTrue(codes.contains("fixed_placement_moved"), codes.toString());
        assertTrue(codes.contains("unexpected_fixed_placement"), codes.toString());
    }

    @Test
    void theValidatorCatchesAMissingFixedContainer() {
        Item cube = Item.builder("cube", Dimensions.mm(100, 100, 100)).quantity(5).build();
        Container box = Container.builder("box", Dimensions.mm(200, 100, 200)).quantity(3L).build();
        FixedPlacement wanted = new FixedPlacement("cube", "box", at(100, 0, 0), Rotation.LWH, 2);
        PackingRequest request = new PackingRequest(List.of(cube), List.of(box), List.of(wanted));
        List<ValidationIssue> issues = new IndependentSolutionValidator().validate(request, List.of()).issues();
        assertEquals(List.of(new ValidationIssue("fixed_container_missing", "cube in box#2 at (1600000, 0, 0) LWH")),
                issues);
    }

    private static Point at(long xMm, long yMm, long zMm) {
        return new Point(Length.mm(xMm).ticks(), Length.mm(yMm).ticks(), Length.mm(zMm).ticks());
    }
}
