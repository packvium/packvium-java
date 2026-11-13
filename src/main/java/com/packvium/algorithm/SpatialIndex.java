package com.packvium.algorithm;

import java.util.Arrays;

/**
 * Uniform-grid broad phase for axis-aligned box collision queries.
 *
 * <p>A cell assignment only needs to be a superset of the boxes a query could intersect:
 * {@link #query} returns indices, and the caller still runs the exact AABB test on each, so an
 * error here can only make the index slower, never admit a collision. Query cost is
 * O(cells touched + boxes in them) instead of O(all boxes).
 *
 * <p>Cells live in one flat array over the container's grid rather than a map keyed by cell:
 * the candidate scan visits a few dozen cells per query, and an array offset costs less than
 * hashing a key. A solid is only ever registered inside the grid, so a query reaching past it
 * can skip those cells: they are empty by construction.
 */
public final class SpatialIndex {
    private static final long CELLS_PER_AXIS = 8;
    private static final int[] NONE = new int[0];

    private final long cellX;
    private final long cellY;
    private final long cellZ;
    private final int countX;
    private final int countY;
    private final int countZ;
    /** Per cell, the registered indices; only the first {@code sizes[cell]} entries are live. */
    private final int[][] cells;
    private final int[] sizes;
    /**
     * Query scratch: {@code seen[index] == generation} marks an index already emitted by the
     * current query, so deduplication is O(1) per entry without clearing between queries.
     * Never shared with a copy, and never part of the index's contents.
     */
    private int[] seen = NONE;
    private int generation;
    /** One past the largest registered index: what {@link #seen} must cover. */
    private int indexLimit;

    public SpatialIndex(long lengthTicks, long widthTicks, long heightTicks) {
        cellX = Math.max(1, ceilDiv(Math.max(1, lengthTicks), CELLS_PER_AXIS));
        cellY = Math.max(1, ceilDiv(Math.max(1, widthTicks), CELLS_PER_AXIS));
        cellZ = Math.max(1, ceilDiv(Math.max(1, heightTicks), CELLS_PER_AXIS));
        countX = (int) ceilDiv(Math.max(1, lengthTicks), cellX);
        countY = (int) ceilDiv(Math.max(1, widthTicks), cellY);
        countZ = (int) ceilDiv(Math.max(1, heightTicks), cellZ);
        cells = new int[countX * countY * countZ][];
        sizes = new int[cells.length];
    }

    private SpatialIndex(SpatialIndex source) {
        cellX = source.cellX;
        cellY = source.cellY;
        cellZ = source.cellZ;
        countX = source.countX;
        countY = source.countY;
        countZ = source.countZ;
        cells = new int[source.cells.length][];
        sizes = source.sizes.clone();
        indexLimit = source.indexLimit;
        for (int cell = 0; cell < cells.length; cell++) {
            if (source.cells[cell] != null) {
                cells[cell] = Arrays.copyOf(source.cells[cell], Math.max(1, sizes[cell]));
            }
        }
    }

    /** A structural fork, independent of the original from here on. */
    public SpatialIndex copy() {
        return new SpatialIndex(this);
    }

    public void add(int index, long[] bound) {
        long[] range = cellRange(bound[0], bound[1], bound[2], bound[3], bound[4], bound[5]);
        if (range[1] > countX || range[3] > countY || range[5] > countZ) {
            // Registering a solid outside the grid would let a query miss it; that is a solver
            // defect, so it fails here instead of becoming a silent overlap.
            throw new IllegalStateException("solid extends past the container grid");
        }
        for (long ix = range[0]; ix < range[1]; ix++) {
            for (long iy = range[2]; iy < range[3]; iy++) {
                for (long iz = range[4]; iz < range[5]; iz++) {
                    append(cell(ix, iy, iz), index);
                }
            }
        }
    }

    /**
     * Bound indices sharing at least one cell with the box, each once, in first-seen order.
     * Cells are visited in (x, y, z) order, so the sequence is a deterministic function of the
     * contents; a box touching a single occupied cell gets that bucket back as-is.
     */
    public int[] query(long x1, long y1, long z1, long x2, long y2, long z2) {
        long[] range = cellRange(x1, y1, z1, x2, y2, z2);
        long endX = Math.min(range[1], countX);
        long endY = Math.min(range[3], countY);
        long endZ = Math.min(range[5], countZ);
        int firstHit = -1;
        int hits = 0;
        int total = 0;
        for (long ix = range[0]; ix < endX; ix++) {
            for (long iy = range[2]; iy < endY; iy++) {
                for (long iz = range[4]; iz < endZ; iz++) {
                    int cell = cell(ix, iy, iz);
                    if (sizes[cell] > 0) {
                        if (hits++ == 0) {
                            firstHit = cell;
                        }
                        total += sizes[cell];
                    }
                }
            }
        }
        if (hits == 0) {
            return NONE;
        }
        if (hits == 1) {
            return Arrays.copyOf(cells[firstHit], sizes[firstHit]);
        }
        int[] out = new int[total];
        int count = 0;
        int mark = nextGeneration();
        for (long ix = range[0]; ix < endX; ix++) {
            for (long iy = range[2]; iy < endY; iy++) {
                for (long iz = range[4]; iz < endZ; iz++) {
                    int cell = cell(ix, iy, iz);
                    int[] bucket = cells[cell];
                    for (int position = 0; position < sizes[cell]; position++) {
                        int index = bucket[position];
                        if (seen[index] != mark) {
                            seen[index] = mark;
                            out[count++] = index;
                        }
                    }
                }
            }
        }
        return count == total ? out : Arrays.copyOf(out, count);
    }

    /** Indices registered in the cell that contains the point. */
    public int[] bucketAt(long x, long y, long z) {
        long ix = x / cellX;
        long iy = y / cellY;
        long iz = z / cellZ;
        if (ix >= countX || iy >= countY || iz >= countZ) {
            return NONE;
        }
        int cell = cell(ix, iy, iz);
        return sizes[cell] == 0 ? NONE : Arrays.copyOf(cells[cell], sizes[cell]);
    }

    /**
     * Every index registered in a cell of the ray below {@code ceiling} through the point on the
     * other two axes, possibly more than once. A solid that ends at or under the ceiling and
     * covers the point on those axes is registered in one of these cells, which is what lets
     * ContainerState's surface projections skip every other bound.
     */
    public int[] columnZ(long x, long y, long ceiling) {
        long ix = x / cellX;
        long iy = y / cellY;
        if (ix >= countX || iy >= countY) {
            return NONE;
        }
        return gather(cell(ix, iy, 0), 1, Math.min(ceilDiv(ceiling, cellZ), countZ));
    }

    public int[] columnY(long x, long z, long ceiling) {
        long ix = x / cellX;
        long iz = z / cellZ;
        if (ix >= countX || iz >= countZ) {
            return NONE;
        }
        return gather(cell(ix, 0, iz), countZ, Math.min(ceilDiv(ceiling, cellY), countY));
    }

    public int[] columnX(long y, long z, long ceiling) {
        long iy = y / cellY;
        long iz = z / cellZ;
        if (iy >= countY || iz >= countZ) {
            return NONE;
        }
        return gather(cell(0, iy, iz), countY * countZ, Math.min(ceilDiv(ceiling, cellX), countX));
    }

    /** The buckets of {@code steps} cells from {@code first}, {@code stride} apart, concatenated. */
    private int[] gather(int first, int stride, long steps) {
        int total = 0;
        for (int step = 0, cell = first; step < steps; step++, cell += stride) {
            total += sizes[cell];
        }
        if (total == 0) {
            return NONE;
        }
        int[] out = new int[total];
        int count = 0;
        for (int step = 0, cell = first; step < steps; step++, cell += stride) {
            System.arraycopy(cells[cell] == null ? NONE : cells[cell], 0, out, count, sizes[cell]);
            count += sizes[cell];
        }
        return out;
    }

    private int nextGeneration() {
        if (seen.length < indexLimit) {
            // Allocated lazily, so a fork that is never queried pays nothing for it.
            seen = new int[Math.max(16, Math.max(indexLimit, seen.length * 2))];
            generation = 0;
        }
        if (++generation == Integer.MAX_VALUE) {
            // Wrapped: stale marks could collide with a new generation, so start clean.
            Arrays.fill(seen, 0);
            generation = 1;
        }
        return generation;
    }

    private void append(int cell, int index) {
        indexLimit = Math.max(indexLimit, index + 1);
        int[] bucket = cells[cell];
        if (bucket == null) {
            bucket = new int[4];
        } else if (sizes[cell] == bucket.length) {
            bucket = Arrays.copyOf(bucket, bucket.length * 2);
        }
        bucket[sizes[cell]++] = index;
        cells[cell] = bucket;
    }

    private int cell(long ix, long iy, long iz) {
        return (int) ((ix * countY + iy) * countZ + iz);
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
}
