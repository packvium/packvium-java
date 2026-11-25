package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.packvium.serialization.JsonReader;
import com.packvium.serialization.RequestPacker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The `quality` portfolio stages its starts as Python does: every start greedy, then one
 * late-type neighborhood of the best greedy extreme-point order per item type, then one beam
 * refinement of the best extreme-point order. Expected values are Python's own output at tag
 * v1.6.0 for this request.
 */
class QualityStagingTest {
    private static final String REQUEST = """
            {"units": {"length": "mm"},
             "configuration": {"solver_profile": "quality", "time_limit_ms": 60000,
                               "effort_budget": {"max_search_nodes": 2000}},
             "items": [{"id": "slab", "quantity": 2, "dimensions": {"length": "200", "width": "100", "height": "50"}},
                       {"id": "cube", "quantity": 3, "dimensions": {"length": "100", "width": "100", "height": "100"}}],
             "containers": [{"id": "tote", "inner_dimensions": {"length": "200", "width": "200", "height": "150"}}]}
            """;

    @Test
    void theStartsAreNamedRunAndChosenAsInPython() {
        Map<String, Object> result = RequestPacker.pack(JsonReader.parse(REQUEST));
        List<?> starts = (List<?>) ((Map<?, ?>) result.get("termination")).get("starts");
        List<String> ids = starts.stream().map(start -> (String) ((Map<?, ?>) start).get("id")).toList();
        List<String> orders = List.of("small_edge", "volume", "seeded_2", "seeded_3", "seeded_4", "seeded_5",
                "seeded_6", "seeded_7");
        List<String> expected = new ArrayList<>();
        orders.forEach(order -> expected.add("exact_small:" + order + ":greedy"));
        expected.add("homogeneous_blocks:small_edge");
        orders.forEach(order -> expected.add("extreme_points:" + order + ":greedy"));
        orders.forEach(order -> expected.add("maximal_spaces:" + order + ":greedy"));
        expected.add("layer:small_edge:greedy");
        expected.add("extreme_points:seeded_3:greedy:late_cube");
        expected.add("extreme_points:seeded_3:greedy:late_slab");
        expected.add("extreme_points:seeded_3:greedy:beam");
        assertEquals(expected, ids);
        assertEquals(List.of("exact_small:seeded_2:greedy"), starts.stream().map(start -> (Map<?, ?>) start)
                .filter(start -> Boolean.TRUE.equals(start.get("selected"))).map(start -> start.get("id")).toList());
        assertEquals("exact_small:seeded_2:greedy", ((Map<?, ?>) result.get("algorithm")).get("solver"));
        assertEquals(List.of(0L, 1L, 0L, 166666L, 1000000L), result.get("score"));
    }
}
