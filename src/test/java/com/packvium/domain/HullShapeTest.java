package com.packvium.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Expected values come from the v1.0.0 PHP reference engine. */
class HullShapeTest {
    private static final List<Vertex> WEDGE = List.of(new Vertex(0, 0, 0), new Vertex(100, 0, 0),
            new Vertex(0, 100, 0), new Vertex(100, 100, 0), new Vertex(0, 0, 100), new Vertex(100, 0, 100));
    private static final List<Vertex> COUNTER_WEDGE = List.of(new Vertex(0, 100, 0), new Vertex(100, 100, 0),
            new Vertex(0, 0, 100), new Vertex(100, 0, 100), new Vertex(0, 100, 100), new Vertex(100, 100, 100));
    private static final long[] ORIGIN = {0, 0, 0};

    @Test
    void wedgeHasExactVolumeFacesAndEdges() {
        HullShape wedge = HullShape.of(WEDGE);
        assertEquals(BigInteger.valueOf(500_000), wedge.volume());
        assertEquals(List.of(new Vertex(0, 0, 1), new Vertex(0, 1, 0), new Vertex(0, 1, 1), new Vertex(1, 0, 0)),
                wedge.faceAxes());
        assertEquals(List.of(new Vertex(0, 0, 1), new Vertex(0, 1, -1), new Vertex(0, 1, 0), new Vertex(1, 0, 0)),
                wedge.edgeDirections());
    }

    @Test
    void oddRotationsTurnRatherThanMirror() {
        assertEquals(List.of(new Vertex(0, 0, 0), new Vertex(0, 0, 100), new Vertex(100, 0, 0),
                new Vertex(100, 0, 100), new Vertex(0, 100, 0), new Vertex(0, 100, 100)),
                HullShape.rotate(WEDGE, Rotation.WHL));
        assertEquals(BigInteger.valueOf(500_000), HullShape.shapeFor(WEDGE, Rotation.WHL).volume());
    }

    @Test
    void interlockingWedgesTouchWithoutColliding() {
        HullShape wedge = HullShape.of(WEDGE);
        HullShape counter = HullShape.of(COUNTER_WEDGE);
        HullShape cube = HullShape.box(100, 100, 100);
        assertFalse(HullShape.collide(wedge, ORIGIN, counter, ORIGIN));
        assertTrue(HullShape.collide(wedge, ORIGIN, cube, ORIGIN));
        assertFalse(HullShape.collide(wedge, ORIGIN, cube, new long[] {0, 0, 100}));
        assertTrue(HullShape.collide(wedge, ORIGIN, wedge, new long[] {0, 50, 0}));
        assertEquals(List.of(new Vertex(0, 0, 1), new Vertex(0, 1, 0), new Vertex(0, 1, 1), new Vertex(1, 0, 0)),
                HullShape.separatingAxes(wedge, counter));
    }

    @Test
    void coprimeHullTakesTheExactPathAndAgrees() {
        HullShape big = HullShape.of(List.of(new Vertex(0, 0, 0), new Vertex(99_999_989, 0, 0),
                new Vertex(0, 99_999_971, 0), new Vertex(0, 0, 99_999_959),
                new Vertex(99_999_931, 99_999_883, 99_999_847), new Vertex(12_345_677, 98_765_431, 55_555_553)));
        assertEquals(new BigInteger("569958025411784542165468"), big.volume());
        assertEquals(8, big.faceAxes().size());
        assertEquals(12, big.edgeDirections().size());
        assertTrue(HullShape.collide(big, ORIGIN, big, new long[] {99_999_000, 1, 1}));
        assertTrue(HullShape.collide(big, ORIGIN, big, new long[] {3, 5, 7}));
    }

    @Test
    void verticesInsideAFaceAreNotCorners() {
        HullShape shape = HullShape.of(List.of(new Vertex(50, 0, 50), new Vertex(100, 50, 50),
                new Vertex(50, 100, 50), new Vertex(0, 50, 50), new Vertex(50, 50, 0), new Vertex(50, 50, 100),
                new Vertex(75, 25, 50)));
        assertEquals(BigInteger.valueOf(166_666), shape.volume());
        assertEquals(List.of(new Vertex(0, 1, -1), new Vertex(0, 1, 1), new Vertex(1, -1, 0), new Vertex(1, 0, -1),
                new Vertex(1, 0, 1), new Vertex(1, 1, 0)), shape.edgeDirections());
    }

    @Test
    void compressionIsExactAtTheBoundary() {
        Compression.Pressure pressure = Compression.pressure(8_000_000_000L * 50, 16_000L * 16_000 * 100 * 100);
        assertEquals(new BigInteger("100420096000000000000000000000000"), pressure.numerator());
        assertEquals(new BigInteger("2048000000000000000000000000000"), pressure.denominator());
        assertTrue(Compression.exceeds(pressure, 10));
        assertFalse(Compression.exceeds(pressure, 100));
        assertEquals(1_403_867, Compression.effectiveHeight(1_600_000, 250_000, 100, pressure));
        assertEquals(123_457, Compression.ratioToPpm(0.1234565));
    }

    @Test
    void centreOfMassReportsTheWorseAxis() {
        Dimensions cube = Dimensions.ofTicks(10, 10, 10);
        Item heavy = Item.builder("a", cube).weight(new Weight(3 * Weight.TICKS_PER_KG)).build();
        Item light = Item.builder("b", cube).weight(new Weight(Weight.TICKS_PER_KG)).build();
        List<Placement> placements = List.of(
                new Placement(heavy.instances().get(0), Point.ORIGIN, Rotation.LWH, cube, Point.ORIGIN, cube),
                new Placement(light.instances().get(0), new Point(25, 7, 0), Rotation.LWH, cube,
                        new Point(25, 7, 0), cube));
        assertEquals(550_000, CentreOfMass.offsetPpm(Dimensions.ofTicks(40, 30, 10), placements));
    }
}
