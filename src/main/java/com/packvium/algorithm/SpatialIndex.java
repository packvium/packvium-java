package com.packvium.algorithm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Uniform-grid broad phase for axis-aligned box collision queries.
 *
 * <p>A cell assignment only needs to be a superset of the boxes a query could intersect:
 * {@link #query} returns indices, and the caller still runs the exact AABB test on each, so an
 * error here can only make the index slower, never admit a collision. Query cost is
 * O(cells touched + boxes in them) instead of O(all boxes).
 */
public final class SpatialIndex {
    private static final long CELLS_PER_AXIS = 8;

    private final long cellX;
    private final long cellY;
    private final long cellZ;
    private final Map<CellKey, List<Integer>> cells;

    public SpatialIndex(long lengthTicks, long widthTicks, long heightTicks) {
        cellX = Math.max(1, ceilDiv(Math.max(1, lengthTicks), CELLS_PER_AXIS));
        cellY = Math.max(1, ceilDiv(Math.max(1, widthTicks), CELLS_PER_AXIS));
        cellZ = Math.max(1, ceilDiv(Math.max(1, heightTicks), CELLS_PER_AXIS));
        cells = new HashMap<>();
    }

    private SpatialIndex(SpatialIndex source) {
        cellX = source.cellX;
        cellY = source.cellY;
        cellZ = source.cellZ;
        cells = new HashMap<>();
        source.cells.forEach((key, indices) -> cells.put(key, new ArrayList<>(indices)));
    }

    /** A structural fork, independent of the original from here on. */
    public SpatialIndex copy() {
        return new SpatialIndex(this);
    }

    public void add(int index, long[] bound) {
        long[] range = cellRange(bound[0], bound[1], bound[2], bound[3], bound[4], bound[5]);
        for (long ix = range[0]; ix < range[1]; ix++) {
            for (long iy = range[2]; iy < range[3]; iy++) {
                for (long iz = range[4]; iz < range[5]; iz++) {
                    cells.computeIfAbsent(new CellKey(ix, iy, iz), key -> new ArrayList<>()).add(index);
                }
            }
        }
    }

    /** Bound indices sharing at least one cell with the box, each once, in first-seen order. */
    public List<Integer> query(long x1, long y1, long z1, long x2, long y2, long z2) {
        long[] range = cellRange(x1, y1, z1, x2, y2, z2);
        LinkedHashSet<Integer> found = new LinkedHashSet<>();
        for (long ix = range[0]; ix < range[1]; ix++) {
            for (long iy = range[2]; iy < range[3]; iy++) {
                for (long iz = range[4]; iz < range[5]; iz++) {
                    List<Integer> indices = cells.get(new CellKey(ix, iy, iz));
                    if (indices != null) {
                        found.addAll(indices);
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    private long[] cellRange(long x1, long y1, long z1, long x2, long y2, long z2) {
        return new long[] {
            x1 / cellX, ceilDiv(Math.max(x2, x1 + 1), cellX),
            y1 / cellY, ceilDiv(Math.max(y2, y1 + 1), cellY),
            z1 / cellZ, ceilDiv(Math.max(z2, z1 + 1), cellZ),
        };
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private record CellKey(long x, long y, long z) {
    }
}
