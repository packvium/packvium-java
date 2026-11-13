package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The index may over-report but must never miss a solid; checked against a brute-force scan. */
class SpatialIndexTest {
    private static final long LENGTH = 97;
    private static final long WIDTH = 61;
    private static final long HEIGHT = 43;

    @Test
    void everyQueryCoversTheSolidsItIntersects() {
        List<long[]> bounds = solids();
        SpatialIndex index = indexOf(bounds);
        for (long x = 0; x < LENGTH; x += 7) {
            for (long y = 0; y < WIDTH; y += 5) {
                for (long z = 0; z < HEIGHT; z += 3) {
                    int[] hits = index.query(x, y, z, Math.min(LENGTH, x + 11), Math.min(WIDTH, y + 9),
                            Math.min(HEIGHT, z + 4));
                    assertEquals(hits.length, new LinkedHashSet<>(boxed(hits)).size(), "duplicate index");
                    Set<Integer> found = Set.copyOf(boxed(hits));
                    for (int solid = 0; solid < bounds.size(); solid++) {
                        long[] b = bounds.get(solid);
                        if (x < b[3] && b[0] < x + 11 && y < b[4] && b[1] < y + 9 && z < b[5] && b[2] < z + 4) {
                            assertTrue(found.contains(solid), "query missed solid " + solid);
                        }
                    }
                }
            }
        }
    }

    @Test
    void bucketAndColumnsCoverTheSolidsAtAPoint() {
        List<long[]> bounds = solids();
        SpatialIndex index = indexOf(bounds);
        for (long x = 0; x < LENGTH; x += 4) {
            for (long y = 0; y < WIDTH; y += 3) {
                for (long z = 0; z < HEIGHT; z += 2) {
                    Set<Integer> bucket = Set.copyOf(boxed(index.bucketAt(x, y, z)));
                    Set<Integer> columnZ = Set.copyOf(boxed(index.columnZ(x, y, z)));
                    Set<Integer> columnY = Set.copyOf(boxed(index.columnY(x, z, y)));
                    Set<Integer> columnX = Set.copyOf(boxed(index.columnX(y, z, x)));
                    for (int solid = 0; solid < bounds.size(); solid++) {
                        long[] b = bounds.get(solid);
                        boolean onX = b[0] <= x && x < b[3];
                        boolean onY = b[1] <= y && y < b[4];
                        boolean onZ = b[2] <= z && z < b[5];
                        if (onX && onY && onZ) {
                            assertTrue(bucket.contains(solid), "bucket missed solid " + solid);
                        }
                        if (onX && onY && b[5] <= z) {
                            assertTrue(columnZ.contains(solid), "column z missed solid " + solid);
                        }
                        if (onX && onZ && b[4] <= y) {
                            assertTrue(columnY.contains(solid), "column y missed solid " + solid);
                        }
                        if (onY && onZ && b[3] <= x) {
                            assertTrue(columnX.contains(solid), "column x missed solid " + solid);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aCopyIsIndependentOfItsSource() {
        SpatialIndex source = indexOf(List.of(new long[] {0, 0, 0, 10, 10, 10}));
        SpatialIndex fork = source.copy();
        fork.add(1, new long[] {0, 0, 0, 5, 5, 5});
        assertArrayEquals(new int[] {0}, source.query(0, 0, 0, 1, 1, 1));
        assertArrayEquals(new int[] {0, 1}, fork.query(0, 0, 0, 1, 1, 1));
    }

    @Test
    void refusesASolidOutsideTheGrid() {
        SpatialIndex index = new SpatialIndex(LENGTH, WIDTH, HEIGHT);
        assertThrows(IllegalStateException.class, () -> index.add(0, new long[] {0, 0, 0, LENGTH + 20, 1, 1}));
    }

    /** A fixed spread of solids of uneven sizes, so many of them straddle cell borders. */
    private static List<long[]> solids() {
        List<long[]> bounds = new ArrayList<>();
        for (long seed = 0; seed < 40; seed++) {
            long x = (seed * 37) % (LENGTH - 1);
            long y = (seed * 23) % (WIDTH - 1);
            long z = (seed * 11) % (HEIGHT - 1);
            bounds.add(new long[] {x, y, z, Math.min(LENGTH, x + 1 + seed % 17), Math.min(WIDTH, y + 1 + seed % 13),
                Math.min(HEIGHT, z + 1 + seed % 7)});
        }
        return bounds;
    }

    private static SpatialIndex indexOf(List<long[]> bounds) {
        SpatialIndex index = new SpatialIndex(LENGTH, WIDTH, HEIGHT);
        for (int solid = 0; solid < bounds.size(); solid++) {
            index.add(solid, bounds.get(solid));
        }
        return index;
    }

    private static List<Integer> boxed(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int value : values) {
            out.add(value);
        }
        return out;
    }
}
