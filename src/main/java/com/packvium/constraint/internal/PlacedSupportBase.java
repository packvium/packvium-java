package com.packvium.constraint.internal;

import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.LoadUnit;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Placement;
import java.util.ArrayList;
import java.util.List;

/**
 * The support graph over one placement list, with each candidate appended to it.
 *
 * <p>The support graph over the <em>placed</em> boxes is the same for every candidate evaluated
 * against one search state, so every rule that reads the graph -- bearing and route order -- keeps
 * one of these instead of rebuilding it per candidate. The cache is deliberately a single entry:
 * search evaluates a run of candidates against one state before moving on, so one entry captures
 * the whole run. Per candidate: O(n) for the delta graph. Mutable per-solve state, so one instance
 * must not be shared between concurrent solves.
 */
public final class PlacedSupportBase {
    private List<Placement> placements;
    private LoadSupportGraph base;
    private List<LoadUnit> baseUnits = List.of();
    private long hint = 1;

    /** The placed units and the candidate {@code unit} appended at the last index, with their graph. */
    public Extended withCandidate(List<Placement> current, LoadUnit unit) {
        AxisAlignedBox box = unit.box();
        long footprint = Math.max(box.x2() - box.origin().x(), box.y2() - box.origin().y());
        LoadSupportGraph graph = baseFor(current, footprint).withUnit(unit, hint);
        List<LoadUnit> units = new ArrayList<>(baseUnits.size() + 1);
        units.addAll(baseUnits);
        units.add(unit);
        return new Extended(units, graph);
    }

    /**
     * The support graph over {@code current} alone, rebuilt only when it cannot serve. The cell
     * hint has to cover every candidate appended to this base, and the widest is not known in
     * advance, so it grows to fit the first candidate that needs it and the base is rebuilt that
     * once. Sizing it from the container instead would always be safe and always coarse.
     */
    private LoadSupportGraph baseFor(List<Placement> current, long footprint) {
        boolean same = base != null && samePlacements(placements, current);
        if (same && footprint <= hint) {
            return base;
        }
        hint = Math.max(same ? hint : 1, footprint);
        placements = current;
        baseUnits = List.copyOf(LoadCalculator.units(current));
        base = new LoadSupportGraph(baseUnits, hint);
        return base;
    }

    /** Same elements, same order, compared by identity. O(n), O(1) for the same list. */
    private static boolean samePlacements(List<Placement> left, List<Placement> right) {
        if (left == right) {
            return true;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) {
                return false;
            }
        }
        return true;
    }

    /** The units of a placement list plus one candidate, and their support graph. */
    public record Extended(List<LoadUnit> units, LoadSupportGraph graph) {
    }
}
