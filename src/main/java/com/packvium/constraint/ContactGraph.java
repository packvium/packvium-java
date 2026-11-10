package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct top/bottom contact between boxes, derived from geometry alone.
 *
 * <p>Built once per whole-container check and shared by every load-propagation computation
 * -- two of them disagreeing on what "touches" means was a standing risk nothing
 * forced them to share the definition against.
 *
 * <p>Build is O(n + sum of per-level near-candidates), O(n) for an evenly spread lattice where
 * a level-wide scan was O(level_size^2); {@link #withBox} is O(n) for the outer
 * copy plus O(k) for the two planes it queries. Edge lists are in ascending index order,
 * which is contract: the load split hands its rounding remainder to the last edge.
 */
public final class ContactGraph {
    private static final Comparator<long[]> BY_INDEX = Comparator.comparingLong(match -> match[0]);

    private final List<List<ContactEdge>> supporters;
    private final List<List<Integer>> children;
    private final List<AxisAlignedBox> boxes;
    private final long cell;
    private final Map<Long, List<ContactLevelIndex.Entry>> byTop;
    private final Map<Long, List<ContactLevelIndex.Entry>> byBottom;
    private final Map<Long, ContactLevelIndex> topIndexes;
    private final Map<Long, ContactLevelIndex> bottomIndexes;

    public ContactGraph(List<AxisAlignedBox> boxes) {
        this(boxes, 1);
    }

    /**
     * {@code cellHint} is an upper bound on the footprint of any box that may later be appended
     * with {@link #withBox}. Without it the cell is sized from the boxes present now, and
     * appending anything wider falls back to a full rebuild -- correct but defeating the
     * point, because in a search the candidate is a new item that may be the widest in the
     * request. Too large a hint only makes each bucket coarser.
     */
    public ContactGraph(List<AxisAlignedBox> boxes, long cellHint) {
        Map<Long, List<ContactLevelIndex.Entry>> top = new HashMap<>();
        Map<Long, List<ContactLevelIndex.Entry>> bottom = new HashMap<>();
        for (int index = 0; index < boxes.size(); index++) {
            AxisAlignedBox box = boxes.get(index);
            top.computeIfAbsent(box.z2(), ignored -> new ArrayList<>()).add(new ContactLevelIndex.Entry(index, box));
            bottom.computeIfAbsent(box.origin().z(), ignored -> new ArrayList<>())
                    .add(new ContactLevelIndex.Entry(index, box));
        }
        List<List<ContactEdge>> supporterLists = new ArrayList<>(boxes.size());
        List<List<Integer>> childLists = new ArrayList<>(boxes.size());
        for (int index = 0; index < boxes.size(); index++) {
            supporterLists.add(new ArrayList<>());
            childLists.add(new ArrayList<>());
        }
        // A single global cell size, not one derived per level from that level's own
        // candidates: a querying box can be any size in this scene, and ContactLevelIndex is
        // only correct when its cell is at least as large as every box it will ever index or
        // be queried with.
        long cellSize = Math.max(1, cellHint);
        for (AxisAlignedBox box : boxes) {
            cellSize = Math.max(cellSize, Math.max(box.x2() - box.origin().x(), box.y2() - box.origin().y()));
        }
        Map<Long, ContactLevelIndex> indexes = new HashMap<>();
        for (int index = 0; index < boxes.size(); index++) {
            AxisAlignedBox box = boxes.get(index);
            List<ContactLevelIndex.Entry> candidates = top.get(box.origin().z());
            if (candidates == null) {
                continue;
            }
            long size = cellSize;
            ContactLevelIndex level = indexes.computeIfAbsent(box.origin().z(),
                    ignored -> new ContactLevelIndex(size, candidates));
            // The load split hands the rounding remainder to whichever edge is *last*, so this
            // must land in ascending otherIndex order, not the spatial hash's cell order.
            List<long[]> matches = new ArrayList<>();
            for (ContactLevelIndex.Entry entry : level.near(box)) {
                if (entry.index() == index) {
                    continue;
                }
                matches.add(new long[] {entry.index(), entry.box().overlapAreaXY(box)});
            }
            matches.sort(BY_INDEX);
            for (long[] match : matches) {
                if (match[1] > 0) {
                    supporterLists.get(index).add(new ContactEdge((int) match[0], match[1]));
                    childLists.get((int) match[0]).add(index);
                }
            }
        }
        this.supporters = freeze(supporterLists);
        this.children = freeze(childLists);
        this.boxes = List.copyOf(boxes);
        this.cell = cellSize;
        this.byTop = top;
        this.byBottom = bottom;
        // Only the top-plane indexes are populated by the build above; the bottom-plane ones
        // are built on demand, because a from-scratch build never needs them.
        this.topIndexes = indexes;
        this.bottomIndexes = new HashMap<>();
    }

    private ContactGraph(List<List<ContactEdge>> supporters, List<List<Integer>> children,
            List<AxisAlignedBox> boxes, long cell, Map<Long, List<ContactLevelIndex.Entry>> byTop,
            Map<Long, List<ContactLevelIndex.Entry>> byBottom, Map<Long, ContactLevelIndex> topIndexes,
            Map<Long, ContactLevelIndex> bottomIndexes) {
        this.supporters = supporters;
        this.children = children;
        this.boxes = boxes;
        this.cell = cell;
        this.byTop = byTop;
        this.byBottom = byBottom;
        this.topIndexes = topIndexes;
        this.bottomIndexes = bottomIndexes;
    }

    /**
     * This graph plus one more box, appended at the next index.
     *
     * <p>Adding a box cannot create or destroy contact between two boxes already here: contact
     * is a pairwise geometric predicate. That is why a delta is sound, and why this shares the
     * base's edge lists instead of recomputing them. The result is required to be identical to
     * {@code new ContactGraph([...boxes, box])}, not merely equivalent, because edge order is
     * contract. The new box takes the highest index, so appending keeps every list ascending.
     */
    public ContactGraph withBox(AxisAlignedBox box) {
        int index = boxes.size();
        long footprint = Math.max(box.x2() - box.origin().x(), box.y2() - box.origin().y());
        if (footprint > cell) {
            // A larger box could step over cells in the middle of its own footprint and miss a
            // real overlap, so this is a correctness fallback, not an optimisation choice.
            List<AxisAlignedBox> extended = new ArrayList<>(boxes);
            extended.add(box);
            return new ContactGraph(extended, footprint);
        }
        // Both queries run before the copy so that any level index they build lands on this
        // graph first and the copy inherits it.
        List<long[]> below = matches(byTop, topIndexes, box.origin().z(), box);
        List<long[]> above = matches(byBottom, bottomIndexes, box.z2(), box);

        List<List<ContactEdge>> nextSupporters = new ArrayList<>(supporters);
        List<List<Integer>> nextChildren = new ArrayList<>(children);
        List<ContactEdge> ownSupporters = new ArrayList<>();
        for (long[] match : below) {
            int other = (int) match[0];
            ownSupporters.add(new ContactEdge(other, match[1]));
            nextChildren.set(other, appended(nextChildren.get(other), index));
        }
        // Their supporter lists gain the new index, larger than every index already in them,
        // so ascending order is preserved by appending.
        List<Integer> ownChildren = new ArrayList<>();
        for (long[] match : above) {
            int other = (int) match[0];
            ownChildren.add(other);
            nextSupporters.set(other, appended(nextSupporters.get(other), new ContactEdge(index, match[1])));
        }
        nextSupporters.add(List.copyOf(ownSupporters));
        nextChildren.add(List.copyOf(ownChildren));

        List<AxisAlignedBox> nextBoxes = new ArrayList<>(boxes);
        nextBoxes.add(box);
        // One box joins exactly two planes, so rewriting those two buckets is all that changed.
        Map<Long, List<ContactLevelIndex.Entry>> nextTop = new HashMap<>(byTop);
        nextTop.put(box.z2(), appended(nextTop.getOrDefault(box.z2(), List.of()),
                new ContactLevelIndex.Entry(index, box)));
        Map<Long, List<ContactLevelIndex.Entry>> nextBottom = new HashMap<>(byBottom);
        nextBottom.put(box.origin().z(), appended(nextBottom.getOrDefault(box.origin().z(), List.of()),
                new ContactLevelIndex.Entry(index, box)));
        // A ContactLevelIndex is immutable once built, so every cached one may be shared --
        // except on the two planes whose bucket just gained a member.
        Map<Long, ContactLevelIndex> nextTopIndexes = new HashMap<>(topIndexes);
        nextTopIndexes.remove(box.z2());
        Map<Long, ContactLevelIndex> nextBottomIndexes = new HashMap<>(bottomIndexes);
        nextBottomIndexes.remove(box.origin().z());
        return new ContactGraph(nextSupporters, nextChildren, List.copyOf(nextBoxes), cell, nextTop, nextBottom,
                nextTopIndexes, nextBottomIndexes);
    }

    /** Every box on {@code plane} overlapping {@code box} in XY, as {index, area}, ascending by index. */
    private List<long[]> matches(Map<Long, List<ContactLevelIndex.Entry>> buckets,
            Map<Long, ContactLevelIndex> cache, long plane, AxisAlignedBox box) {
        List<ContactLevelIndex.Entry> bucket = buckets.get(plane);
        if (bucket == null) {
            return List.of();
        }
        ContactLevelIndex level = cache.computeIfAbsent(plane, ignored -> new ContactLevelIndex(cell, bucket));
        List<long[]> found = new ArrayList<>();
        for (ContactLevelIndex.Entry entry : level.near(box)) {
            long area = entry.box().overlapAreaXY(box);
            if (area > 0) {
                found.add(new long[] {entry.index(), area});
            }
        }
        found.sort(BY_INDEX);
        return found;
    }

    /** What {@code index} directly rests on, each with the contact area. */
    public List<ContactEdge> supporters(int index) {
        return supporters.get(index);
    }

    /** What rests directly on {@code index} (not transitively). */
    public List<Integer> children(int index) {
        return children.get(index);
    }

    private static <T> List<T> appended(List<T> list, T value) {
        List<T> copy = new ArrayList<>(list.size() + 1);
        copy.addAll(list);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static <T> List<List<T>> freeze(List<List<T>> lists) {
        List<List<T>> frozen = new ArrayList<>(lists.size());
        for (List<T> list : lists) {
            frozen.add(List.copyOf(list));
        }
        return frozen;
    }
}
