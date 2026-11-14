package com.packvium.locks;

import com.packvium.serialization.JsonReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scenes the reference's lock tests and lock conformance scenarios pack, and the readings of a
 * result they assert on.
 *
 * <p>The two shared corpus fixtures among them, {@code exact-fit} and
 * {@code feature-route-unloading-enforced}, are inlined: they are identical at every tag that has
 * locks, and the lock tests must run without a tag checkout.
 */
final class LockFixtures {
    /** Millimetres in length ticks. */
    static final long MM = 16_000;
    static final long MM100 = 100 * MM;

    /** Eight 100 mm cubes that exactly fill a 200 mm box: the corpus's simplest tiling. */
    static final String EIGHT_CUBES = """
            {"configuration": {"solver_profile": "fast", "time_limit_ms": 300000, "alternatives": 1,
              "effort_budget": {"max_candidates_evaluated": 1000000, "max_placement_attempts": 1000000,
                                "max_search_nodes": 1000000}},
             "items": [{"id": "cube", "quantity": 8,
                        "dimensions": {"length": "100", "width": "100", "height": "100"}}],
             "containers": [{"id": "box", %s
                             "inner_dimensions": {"length": "200", "width": "200", "height": "200"}}]}
            """;

    static final String ROUTE = """
            {"units": {"length": "mm"},
             "configuration": {"solver_profile": "fast", "time_limit_ms": 300000,
               "effort_budget": {"max_candidates_evaluated": 1000000, "max_placement_attempts": 1000000,
                                 "max_search_nodes": 1000000}},
             "items": [
               {"id": "first-stop", "quantity": 1, "stop_index": 0,
                "dimensions": {"length": "10", "width": "10", "height": "10"}},
               {"id": "last-stop", "quantity": 1, "stop_index": 1,
                "dimensions": {"length": "10", "width": "10", "height": "10"}}],
             "containers": [{"id": "column", "inner_dimensions": {"length": "10", "width": "10", "height": "20"}}]}
            """;

    /** One cube in a column twice its height, fully supported or not at all. */
    static final String UNSUPPORTED_COLUMN = """
            {"configuration": {"solver_profile": "fast", "time_limit_ms": 300000, "minimum_support_ratio": 1.0},
             "items": [{"id": "cube", "quantity": 1,
                        "dimensions": {"length": "100", "width": "100", "height": "100"}}],
             "containers": [{"id": "box", "inner_dimensions": {"length": "100", "width": "100", "height": "200"}}]}
            """;

    /** A base that refuses any load at all, and one item that would sit on it. */
    static final String ZERO_TOP_LOAD = """
            {"configuration": {"solver_profile": "balanced", "time_limit_ms": 300000},
             "items": [
               {"id": "base", "quantity": 1, "must_be_on_floor": true,
                "dimensions": {"length": "200", "width": "200", "height": "100"},
                "weight": {"value": "10", "unit": "kg"}, "max_top_load": {"value": "0", "unit": "kg"}},
               {"id": "filler", "quantity": 1,
                "dimensions": {"length": "200", "width": "200", "height": "100"},
                "weight": {"value": "1", "unit": "kg"}}],
             "containers": [{"id": "van", "inner_dimensions": {"length": "200", "width": "200", "height": "200"}}]}
            """;

    private LockFixtures() {
    }

    static Map<String, Object> eightCubes() {
        return parse(EIGHT_CUBES.formatted(""));
    }

    /** The same scene with two boxes on offer. */
    static Map<String, Object> eightCubesInTwoBoxes() {
        return parse(EIGHT_CUBES.formatted("\"quantity\": 2,"));
    }

    @SuppressWarnings("unchecked") // JsonReader decodes a JSON object into a Map<String, Object>.
    static Map<String, Object> parse(String json) {
        return (Map<String, Object>) JsonReader.parse(json);
    }

    static int count(Map<String, ?> result) {
        int placed = 0;
        for (Object container : (List<?>) result.get("containers")) {
            placed += ((List<?>) ((Map<?, ?>) container).get("placements")).size();
        }
        return placed;
    }

    /** {@code (container index, item type, orientation, position ticks)} of every placement. */
    static Set<List<Object>> placed(Map<String, ?> result) {
        Set<List<Object>> placed = new HashSet<>();
        List<?> containers = (List<?>) result.get("containers");
        for (int index = 0; index < containers.size(); index++) {
            for (Object entry : (List<?>) ((Map<?, ?>) containers.get(index)).get("placements")) {
                Map<?, ?> placement = (Map<?, ?>) entry;
                placed.add(List.of(index, placement.get("item_type"), placement.get("orientation"),
                        ticks(placement, "position", "x", "y", "z")));
            }
        }
        return placed;
    }

    static List<Long> ticks(Map<?, ?> placement, String field, String... axes) {
        Map<?, ?> scalars = (Map<?, ?>) placement.get(field);
        return Arrays.stream(axes)
                .map(axis -> ((Number) ((Map<?, ?>) scalars.get(axis)).get("ticks")).longValue())
                .toList();
    }

    /**
     * The result with every {@code duration_ms} removed: how long the machine took is a wall clock
     * and not the answer, which is the one field the reference's semantic projection drops for
     * that reason. Everything else -- counters, alternatives, termination -- is still compared.
     */
    static Object withoutClock(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!"duration_ms".equals(key)) {
                    out.put(key, withoutClock(child));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(LockFixtures::withoutClock).toList();
        }
        return value;
    }
}
