package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A uniform spatial hash over one z-level's boxes, bucketed by XY cell so a query only scans
 * the boxes actually near it instead of every other box at that level.
 *
 * <p>{@code cell} must be at least as large as the largest footprint dimension of <em>every</em>
 * box that will ever be inserted into or queried against this index. Only then is a box
 * guaranteed to span no more than a 2x2 block of cells, which guarantees two overlapping boxes
 * always share at least one cell. Build is O(m); a query is O(k) in the boxes sharing its
 * cells. Bucket iteration order never reaches output: every caller sorts its matches by index.
 */
public final class ContactLevelIndex {
    /** One indexed box and its position in the scene. */
    public record Entry(int index, AxisAlignedBox box) {
    }

    private record Cell(long x, long y) {
    }

    private final long cell;
    private final Map<Cell, List<Entry>> buckets = new HashMap<>();

    public ContactLevelIndex(long cell, List<Entry> candidates) {
        this.cell = cell;
        for (Entry entry : candidates) {
            for (Cell key : cells(entry.box())) {
                buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            }
        }
    }

    private Set<Cell> cells(AxisAlignedBox box) {
        long x1 = box.origin().x() / cell;
        long y1 = box.origin().y() / cell;
        long x2 = (box.x2() - 1) / cell;
        long y2 = (box.y2() - 1) / cell;
        Set<Cell> keys = new LinkedHashSet<>(4);
        keys.add(new Cell(x1, y1));
        keys.add(new Cell(x2, y1));
        keys.add(new Cell(x1, y2));
        keys.add(new Cell(x2, y2));
        return keys;
    }

    /**
     * Every candidate that could possibly overlap {@code box} in XY -- a superset of the true
     * overlaps; the caller still checks the overlap area itself. Deduplicated by index.
     */
    public List<Entry> near(AxisAlignedBox box) {
        Set<Integer> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (Cell key : cells(box)) {
            for (Entry entry : buckets.getOrDefault(key, List.of())) {
                if (seen.add(entry.index())) {
                    out.add(entry);
                }
            }
        }
        return out;
    }
}
