package com.packvium.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Expected values come from the v1.0.0 PHP reference engine. */
class SequenceTest {
    private static AxisAlignedBox box(long x, long y, long z, long l, long w, long h) {
        return new AxisAlignedBox(new Point(x, y, z), Dimensions.ofTicks(l, w, h));
    }

    private static final List<AxisAlignedBox> SCENE = List.of(box(0, 0, 0, 10, 10, 10), box(10, 0, 0, 10, 10, 10),
            box(5, 0, 10, 10, 10, 10), box(0, 0, 20, 7, 10, 5), box(20, 0, 0, 3, 3, 3));
    private static final Dimensions CONTAINER = Dimensions.ofTicks(30, 10, 30);

    private static List<Optional<Long>> stops(Long... values) {
        return java.util.Arrays.stream(values).map(Optional::ofNullable).toList();
    }

    @Test
    void ordersMatchTheReference() {
        assertEquals(List.of(3, 2, 0, 1, 4), UnloadingDependencyGraph.safeRemovalOrder(SCENE, CONTAINER));
        assertEquals(List.of(4, 1, 0, 2, 3), LoadingDependencyGraph.safeLoadingOrder(SCENE, CONTAINER));
        assertEquals(List.of(3, 2, 0, 1, 4), UnloadingDependencyGraph.safeRemovalOrder(SCENE, CONTAINER, List.of("-x")));
        assertEquals(List.of(4, 3, 2, 0, 1), UnloadingDependencyGraph.safeRouteRemovalOrder(SCENE,
                stops(3L, 4L, 2L, 1L, 0L), CONTAINER, List.of("+z", "+x")));
    }

    @Test
    void aStuckStopNamesEveryPlacementLeft() {
        RouteSequenceError error = assertThrows(RouteSequenceError.class, () -> UnloadingDependencyGraph
                .safeRouteRemovalOrder(SCENE, stops(2L, 1L, 1L, null, 0L), CONTAINER, List.of("+z")));
        assertEquals("stop 1: placements 1,2 cannot be unloaded there", error.getMessage());
        assertThrows(InvalidDirectionError.class,
                () -> UnloadingDependencyGraph.safeRemovalOrder(SCENE, CONTAINER, List.of("up")));
    }

    @Test
    void reachabilityCarriesSortedEvidence() {
        List<Reachability> reach = UnloadingDependencyGraph.placementReachability(SCENE, CONTAINER,
                stops(1L, 2L, 1L, null, 3L), List.of("+x", "+z"));
        assertEquals(Map.of("index", 0L, "reachable", false, "blocked_by_support", List.of(2L),
                "blocked_by_neighbors", List.of(1L, 2L, 3L, 4L), "blocked_by_route", List.of()), reach.get(0).toMap());
        assertEquals(Map.of("index", 1L, "reachable", false, "blocked_by_support", List.of(2L),
                "blocked_by_neighbors", List.of(2L, 4L), "blocked_by_route", List.of(0L, 2L)), reach.get(1).toMap());
        assertEquals(true, reach.get(3).reachable());
        assertEquals(Set.of(0, 1, 2), reach.get(4).blockedByRoute());
    }

    @Test
    void aSyntheticCycleIsDetected() {
        assertFalse(new LoadingDependencyGraph(Map.of(0, Set.of(1), 1, Set.of(0))).isAcyclic());
        assertEquals(true, UnloadingDependencyGraph.build(SCENE).isAcyclic());
    }
}
