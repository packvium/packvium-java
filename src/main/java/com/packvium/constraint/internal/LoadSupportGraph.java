package com.packvium.constraint.internal;

import com.packvium.constraint.ContactEdge;
import com.packvium.constraint.ContactGraph;
import com.packvium.constraint.LoadUnit;
import com.packvium.constraint.Violation;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Direct load support: ordinary face contact plus exact adjacent nesting contact.
 *
 * <p>Nesting-capable units are sorted by declared item type, exact XY footprint and height, so
 * only neighbours in one possible nested column are compared. Sorting is O(n log n), and
 * scanning/rebuilding the existing contact edges is O(E), not an all-pairs scan. Once an
 * adjacent nesting predecessor exists, all same-column face edges are replaced by that
 * predecessor alone. Final edge and child lists retain ascending unit-index order, preserving
 * ContactGraph's remainder/traversal contract.
 */
public final class LoadSupportGraph {
    private record NestingEntry(String item, long depth, long x1, long y1, long x2, long y2, long z1, long z2,
            int index) {
    }

    private static final Comparator<NestingEntry> NESTING_ORDER = (left, right) -> {
        int item = SortKey.compareCodePoints(left.item(), right.item());
        if (item != 0) {
            return item;
        }
        long[] l = {left.depth(), left.x1(), left.y1(), left.x2(), left.y2(), left.z1(), left.z2(), left.index()};
        long[] r = {right.depth(), right.x1(), right.y1(), right.x2(), right.y2(), right.z1(), right.z2(),
            right.index()};
        for (int position = 0; position < l.length; position++) {
            int order = Long.compare(l[position], r[position]);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    };

    private final ContactGraph face;
    private final List<LoadUnit> units;
    private final boolean nested;
    /** Only populated with nesting; without it this graph <em>is</em> the face graph. */
    private final List<List<ContactEdge>> nestedSupporters;
    private final List<List<Integer>> nestedChildren;

    public LoadSupportGraph(List<LoadUnit> units) {
        this(units, 1);
    }

    public LoadSupportGraph(List<LoadUnit> units, long cellHint) {
        List<NestingEntry> nesting = new ArrayList<>();
        for (int index = 0; index < units.size(); index++) {
            LoadUnit unit = units.get(index);
            if (unit.nestingItemId().isEmpty() || unit.nestingHeightTicks().isEmpty()) {
                continue;
            }
            AxisAlignedBox box = unit.box();
            nesting.add(new NestingEntry(unit.nestingItemId().get(), unit.nestingHeightTicks().get(),
                    box.origin().x(), box.origin().y(), box.x2(), box.y2(), box.origin().z(), box.z2(), index));
        }
        List<AxisAlignedBox> boxes = new ArrayList<>(units.size());
        for (LoadUnit unit : units) {
            boxes.add(unit.box());
        }
        this.face = new ContactGraph(boxes, cellHint);
        this.units = List.copyOf(units);
        this.nested = !nesting.isEmpty();
        if (!nested) {
            this.nestedSupporters = List.of();
            this.nestedChildren = List.of();
            return;
        }
        List<List<ContactEdge>> supporters = new ArrayList<>(units.size());
        for (int index = 0; index < units.size(); index++) {
            supporters.add(face.supporters(index));
        }
        nesting.sort(NESTING_ORDER);
        for (int position = 1; position < nesting.size(); position++) {
            NestingEntry lower = nesting.get(position - 1);
            NestingEntry upper = nesting.get(position);
            if (!sameColumn(lower, upper) || lower.z1() >= upper.z1()) {
                continue;
            }
            int lowerIndex = lower.index();
            int upperIndex = upper.index();
            LoadUnit lowerUnit = units.get(lowerIndex);
            if (lowerUnit.box().z2() - units.get(upperIndex).box().origin().z()
                    != lowerUnit.nestingHeightTicks().get()) {
                continue;
            }
            List<ContactEdge> retained = new ArrayList<>();
            for (ContactEdge edge : supporters.get(upperIndex)) {
                if (!inColumn(units.get(edge.index()), upper)) {
                    retained.add(edge);
                }
            }
            long area = lowerUnit.box().overlapAreaXY(units.get(upperIndex).box());
            int insertAt = 0;
            while (insertAt < retained.size() && retained.get(insertAt).index() < lowerIndex) {
                insertAt++;
            }
            retained.add(insertAt, new ContactEdge(lowerIndex, area));
            supporters.set(upperIndex, List.copyOf(retained));
        }
        List<List<Integer>> children = new ArrayList<>(units.size());
        for (int index = 0; index < units.size(); index++) {
            children.add(new ArrayList<>());
        }
        for (int childIndex = 0; childIndex < supporters.size(); childIndex++) {
            for (ContactEdge edge : supporters.get(childIndex)) {
                children.get(edge.index()).add(childIndex);
            }
        }
        List<List<Integer>> frozen = new ArrayList<>(children.size());
        for (List<Integer> list : children) {
            frozen.add(List.copyOf(list));
        }
        this.nestedSupporters = List.copyOf(supporters);
        this.nestedChildren = List.copyOf(frozen);
    }

    private LoadSupportGraph(ContactGraph face, List<LoadUnit> units) {
        this.face = face;
        this.units = units;
        this.nested = false;
        this.nestedSupporters = List.of();
        this.nestedChildren = List.of();
    }

    private static boolean sameColumn(NestingEntry lower, NestingEntry upper) {
        return lower.item().equals(upper.item()) && lower.depth() == upper.depth() && lower.x1() == upper.x1()
                && lower.y1() == upper.y1() && lower.x2() == upper.x2() && lower.y2() == upper.y2();
    }

    private static boolean inColumn(LoadUnit supporter, NestingEntry upper) {
        return supporter.nestingItemId().isPresent() && supporter.nestingItemId().get().equals(upper.item())
                && supporter.nestingHeightTicks().isPresent() && supporter.nestingHeightTicks().get() == upper.depth()
                && supporter.box().origin().x() == upper.x1() && supporter.box().origin().y() == upper.y1()
                && supporter.box().x2() == upper.x2() && supporter.box().y2() == upper.y2();
    }

    /**
     * This graph plus one more unit, appended at the next index.
     *
     * <p>The search evaluates many candidates against one unchanged set of placements, and
     * rebuilding the whole support graph for each was the cost removes. Adding a box
     * cannot change contact between two boxes already placed, so the face graph only needs its
     * two planes queried. Nesting falls back to a full rebuild: a nesting predecessor
     * <em>replaces</em> the face edges of everything in its column, so one new unit can rewrite
     * edges arbitrarily far from itself and the delta is no longer local.
     */
    public LoadSupportGraph withUnit(LoadUnit unit, long cellHint) {
        List<LoadUnit> extended = new ArrayList<>(units.size() + 1);
        extended.addAll(units);
        extended.add(unit);
        if (nested || unit.nestingItemId().isPresent()) {
            return new LoadSupportGraph(extended, cellHint);
        }
        // Without nesting this graph *is* the face graph, so its edges are read straight off
        // it rather than patching a copy of the old ones -- a second implementation of the same
        // rule would be free to drift from the first.
        return new LoadSupportGraph(face.withBox(unit.box()), List.copyOf(extended));
    }

    public LoadSupportGraph withUnit(LoadUnit unit) {
        return withUnit(unit, 1);
    }

    public List<ContactEdge> supporters(int index) {
        return nested ? nestedSupporters.get(index) : face.supporters(index);
    }

    public List<Integer> children(int index) {
        return nested ? nestedChildren.get(index) : face.children(index);
    }

    /** A direct-support edge may never originate at a non-stackable item. */
    public Optional<Violation> nonStackableFailure(List<Placement> placements, ItemInstance item, int candidateIndex) {
        for (ContactEdge edge : supporters(candidateIndex)) {
            Placement supporter = placements.get(edge.index());
            if (!supporter.instance().item().stackable()) {
                return Optional.of(new Violation("non_stackable", supporter.instance().item().id()));
            }
        }
        if (!item.item().stackable() && !children(candidateIndex).isEmpty()) {
            return Optional.of(new Violation("non_stackable", item.item().id()));
        }
        return Optional.empty();
    }

    private static boolean sameNestingColumn(Placement placement, ItemInstance item, AxisAlignedBox box) {
        AxisAlignedBox other = placement.envelopeBox();
        return placement.instance().item().id().equals(item.item().id())
                && placement.instance().item().nestingHeight().isPresent()
                && item.item().nestingHeight().isPresent()
                && placement.instance().item().nestingHeight().get().ticks()
                        == item.item().nestingHeight().get().ticks()
                && other.origin().x() == box.origin().x() && other.origin().y() == box.origin().y()
                && other.x2() == box.x2() && other.y2() == box.y2();
    }

    private record SupportEntry(int index, AxisAlignedBox box, long area) {
    }

    /**
     * Direct support for one active candidate: O(m) time and O(m) output space.
     *
     * <p>Ordinary face semantics are retained exactly. With an exact adjacent nesting
     * predecessor, shadowed same-column face surfaces are removed and that predecessor becomes
     * one full-area supporter. Every returned list stays in placement-index order, so
     * remainder and traversal decisions remain canonical.
     */
    public static DirectSupportView candidateView(List<Placement> placements, ItemInstance item, AxisAlignedBox box) {
        List<SupportEntry> surfaceEntries = new ArrayList<>();
        List<SupportEntry> supporterEntries = new ArrayList<>();
        SupportEntry nearestLower = null;
        boolean nesting = item.item().nestingHeight().isPresent();
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            AxisAlignedBox other = placement.envelopeBox();
            if (other.z2() == box.origin().z()) {
                surfaceEntries.add(new SupportEntry(index, other, 0));
                long area = other.overlapAreaXY(box);
                if (area > 0) {
                    supporterEntries.add(new SupportEntry(index, other, area));
                }
            }
            if (nesting && placement.instance().item().nestingHeight().isPresent()
                    && sameNestingColumn(placement, item, box)
                    && other.origin().z() < box.origin().z()
                    && (nearestLower == null || other.origin().z() > nearestLower.box().origin().z())) {
                nearestLower = new SupportEntry(index, other, 0);
            }
        }
        SupportEntry predecessor = nearestLower != null && nesting
                && nearestLower.box().z2() - box.origin().z() == item.item().nestingHeight().get().ticks()
                ? nearestLower : null;
        if (predecessor != null) {
            surfaceEntries.removeIf(entry -> sameNestingColumn(placements.get(entry.index()), item, box));
            supporterEntries.removeIf(entry -> sameNestingColumn(placements.get(entry.index()), item, box));
            surfaceEntries.add(insertionPoint(surfaceEntries, predecessor.index()), predecessor);
            supporterEntries.add(insertionPoint(supporterEntries, predecessor.index()),
                    new SupportEntry(predecessor.index(), predecessor.box(), predecessor.box().overlapAreaXY(box)));
        }
        List<Integer> indexes = new ArrayList<>();
        List<AxisAlignedBox> boxes = new ArrayList<>();
        List<Long> areas = new ArrayList<>();
        long totalArea = 0;
        for (SupportEntry entry : supporterEntries) {
            indexes.add(entry.index());
            boxes.add(entry.box());
            areas.add(entry.area());
            totalArea = Math.addExact(totalArea, entry.area());
        }
        List<AxisAlignedBox> surfaces = new ArrayList<>(surfaceEntries.size());
        for (SupportEntry entry : surfaceEntries) {
            surfaces.add(entry.box());
        }
        return new DirectSupportView(surfaces, indexes, boxes, areas, totalArea);
    }

    private static int insertionPoint(List<SupportEntry> entries, int index) {
        int position = 0;
        while (position < entries.size() && entries.get(position).index() < index) {
            position++;
        }
        return position;
    }
}
