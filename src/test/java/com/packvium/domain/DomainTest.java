package com.packvium.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.unit.Weight;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainTest {
    private static final Dimensions CUBE = Dimensions.mm(10, 10, 10);

    @Test
    void uniqueRotationsKeepTheFirstRotationOfEachDistinctBox() {
        List<OrientedDimensions> rotations = Dimensions.mm(10, 10, 20).uniqueRotations(Rotation.all());
        assertEquals(List.of(Rotation.LWH, Rotation.LHW, Rotation.HLW),
                rotations.stream().map(OrientedDimensions::rotation).toList());
    }

    @Test
    void keepUprightNarrowsTheAllowedRotations() {
        Item item = Item.builder("a", CUBE).keepUpright(true).build();
        assertEquals(Rotation.upright(), item.allowedRotations());
        assertThrows(IllegalArgumentException.class,
                () -> Item.builder("a", CUBE).keepUpright(true).allowedRotations(List.of(Rotation.HLW)).build());
    }

    @Test
    void tagsAreDeduplicatedFirstOccurrenceFirst() {
        Item item = Item.builder("a", CUBE).tags(List.of("b", "a", "b")).build();
        assertEquals(List.of("b", "a"), item.tags());
    }

    @Test
    void refusesAShapeFieldOnTheWrongShape() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Item.builder("a", CUBE).compressionRatioPpm(5L).build());
        assertEquals("compression_ratio is not part of a rigid_cuboid item", refusal.getMessage());
    }

    @Test
    void refusesACoplanarHull() {
        List<Vertex> flat = List.of(new Vertex(0, 0, 0), new Vertex(1, 0, 0), new Vertex(0, 1, 0), new Vertex(1, 1, 0));
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> Item.builder("a", CUBE).shapeType(ShapeType.CONVEX_HULL).hullVertices(flat).build());
        assertEquals("convex hull vertices are coplanar and enclose no volume", refusal.getMessage());
    }

    @Test
    void admitsATetrahedronInsideItsBox() {
        List<Vertex> tetra = List.of(new Vertex(0, 0, 0), new Vertex(160000, 0, 0), new Vertex(0, 160000, 0),
                new Vertex(0, 0, 160000));
        Item item = Item.builder("a", CUBE).shapeType(ShapeType.CONVEX_HULL).hullVertices(tetra).build();
        assertEquals(tetra, item.hullVertices().orElseThrow());
    }

    @Test
    void numbersInstancesFromOne() {
        Item item = Item.builder("a", CUBE).quantity(2).build();
        assertEquals(List.of("a#1", "a#2"), item.instances().stream().map(ItemInstance::id).toList());
    }

    @Test
    void refusesAnObstacleOutsideTheContainer() {
        Obstacle outside = new Obstacle("o", new AxisAlignedBox(new Point(0, 0, 1), CUBE));
        assertThrows(IllegalArgumentException.class,
                () -> Container.builder("c", CUBE).obstacles(List.of(outside)).build());
    }

    @Test
    void rateTableChargesTheFirstBracketAtOrAboveTheWeightWithCeilingSurcharge() {
        RateTable table = new RateTable(List.of(500L, 1000L), List.of(700L, 650L), 0, 125);
        assertEquals(788, table.chargeMinor(500));
        assertEquals(732, table.chargeMinor(501));
        assertTrue(table.chargeMinorOrEmpty(1001).isEmpty());
        assertThrows(UnratedWeightException.class, () -> table.chargeMinor(1001));
    }

    @Test
    void dimensionalWeightTruncatesTheExactQuotient() {
        Dimensions box = Dimensions.of("10", "10", "10", "in");
        assertEquals(new Weight(Weight.TICKS_PER_LB * 1000 / 139), box.dimensionalWeight(139, "in", "lb"));
    }

    @Test
    void halfOpenBoxesThatTouchDoNotIntersect() {
        AxisAlignedBox left = new AxisAlignedBox(Point.ORIGIN, CUBE);
        AxisAlignedBox right = new AxisAlignedBox(new Point(160000, 0, 0), CUBE);
        assertFalse(left.intersects(right));
        assertEquals(0, left.overlapAreaXY(right));
    }

    @Test
    void metadataIsImmutable() {
        Item item = Item.builder("a", CUBE).metadata(Map.of("k", "v")).build();
        assertThrows(UnsupportedOperationException.class, () -> item.metadata().put("x", "y"));
    }
}
