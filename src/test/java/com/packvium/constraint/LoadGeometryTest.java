package com.packvium.constraint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Axle;
import com.packvium.domain.AxlePair;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Expected values come from the v1.0.0 PHP reference engine. */
class LoadGeometryTest {
    static AxisAlignedBox box(long x, long y, long z, long l, long w, long h) {
        return new AxisAlignedBox(new Point(x, y, z), Dimensions.ofTicks(l, w, h));
    }

    static final List<AxisAlignedBox> SCENE = List.of(box(0, 0, 0, 10, 10, 10), box(10, 0, 0, 10, 10, 10),
            box(5, 0, 10, 10, 10, 10), box(0, 0, 20, 7, 10, 5), box(20, 0, 0, 3, 3, 3));

    private static List<LoadUnit> units() {
        long[] weights = {1000, 1000, 1001, 333, 5};
        List<LoadUnit> units = new ArrayList<>();
        for (int index = 0; index < SCENE.size(); index++) {
            units.add(new LoadUnit(SCENE.get(index), weights[index],
                    index == 0 ? Optional.of(500L) : Optional.empty(),
                    index == 1 ? Optional.of(1L) : Optional.empty(), "u" + index));
        }
        return units;
    }

    @Test
    void contactGraphFindsFaceContactInIndexOrder() {
        ContactGraph graph = new ContactGraph(SCENE);
        assertEquals(List.of(), graph.supporters(0));
        assertEquals(List.of(new ContactEdge(0, 50), new ContactEdge(1, 50)), graph.supporters(2));
        assertEquals(List.of(new ContactEdge(2, 20)), graph.supporters(3));
        assertEquals(List.of(2), graph.children(0));
        assertEquals(List.of(3), graph.children(2));
        assertEquals(List.of(), graph.children(4));
    }

    @Test
    void appendingABoxMatchesAFreshBuild() {
        ContactGraph incremental = new ContactGraph(SCENE.subList(0, 2), 10);
        for (AxisAlignedBox box : SCENE.subList(2, SCENE.size())) {
            incremental = incremental.withBox(box);
        }
        ContactGraph fresh = new ContactGraph(SCENE);
        for (int index = 0; index < SCENE.size(); index++) {
            assertEquals(fresh.supporters(index), incremental.supporters(index));
            assertEquals(fresh.children(index), incremental.children(index));
        }
        LoadSupportGraph grown = new LoadSupportGraph(units().subList(0, 4), 10).withUnit(units().get(4), 10);
        LoadSupportGraph built = new LoadSupportGraph(units());
        for (int index = 0; index < SCENE.size(); index++) {
            assertEquals(built.supporters(index), grown.supporters(index));
        }
    }

    @Test
    void loadsPropagateWithTheRemainderOnTheLastSupporter() {
        List<LoadUnit> units = units();
        assertArrayEquals(new long[] {667, 667, 333, 0, 0}, LoadCalculator.topLoads(units));
        assertEquals(List.of(Set.of(2, 3), Set.of(2, 3), Set.of(3), Set.of(), Set.of()),
                LoadCalculator.restingAbove(units));
        assertEquals(List.of(2, 3), List.copyOf(LoadCalculator.restingAbove(units).get(0)));
        assertEquals(Optional.of(new Violation("top_load_exceeded", "u0")), LoadCalculator.overloaded(units));
        assertEquals(Optional.of(new Violation("stacked_item_limit_exceeded", "u1")),
                LoadCalculator.stackLimitExceeded(units));
        assertEquals(Optional.of(new Violation("unloading_order_violation", "u0 blocked by u3")),
                LoadCalculator.routeOrderViolated(units,
                        List.of(2.0, Double.POSITIVE_INFINITY, 1.0, 3.0, Double.POSITIVE_INFINITY)));
        assertEquals(Optional.of(new Violation("stack_density_exceeded", "u0")),
                LoadCalculator.stackDensityExceeded(units, Optional.of(1L)));
    }

    @Test
    void axleReactionsAndBalancedOriginsAreExact() {
        AxlePair axles = new AxlePair(new Axle(new Length(1000), Optional.of(new Weight(5000))),
                new Axle(new Length(9000), Optional.of(new Weight(4000))));
        AxleLoad.Reactions reactions = AxleLoad.reactions(axles, units(), 700, 10_000);
        assertEquals(BigInteger.valueOf(16_000), reactions.denominator());
        assertEquals(BigInteger.valueOf(65_639_434), reactions.frontNumerator());
        assertEquals(BigInteger.valueOf(-1_015_434), reactions.rearNumerator());
        assertEquals(Optional.empty(), AxleLoad.exceeded(axles, units(), 700, 10_000));
        assertEquals(List.of(6569L, 11_793L), AxleLoad.axleBalancedOrigins(axles, units(), 700, 10_000, 3001, 77));
    }

    @Test
    void supportPolygonCollapsesInteriorAndCollinearPoints() {
        List<Point2D> points = List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 10),
                new Point2D(0, 10), new Point2D(5, 5), new Point2D(10, 0), new Point2D(5, 0));
        List<Point2D> hull = SupportPolygon.convexHull(points);
        assertEquals(List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 10), new Point2D(0, 10)), hull);
        assertEquals(List.of(new Point2D(0, 0), new Point2D(10, 10)), SupportPolygon.convexHull(
                List.of(new Point2D(0, 0), new Point2D(5, 5), new Point2D(10, 10))));
        assertTrue(SupportPolygon.pointInHull(new Point2D(10, 5), hull));
        assertFalse(SupportPolygon.pointInHull(new Point2D(11, 5), hull));
    }
}
