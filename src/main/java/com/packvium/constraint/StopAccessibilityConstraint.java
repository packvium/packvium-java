package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.SweptRegion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rejects a placement that walls an earlier-stop item away from every door.
 *
 * <p>RouteOrderConstraint enforces the vertical half of route order; this is the horizontal
 * half: nothing due later may stand <em>between</em> an earlier item and the way out. Both are
 * necessary and neither implies the other; the post-validator's whole-scene replay remains the
 * sufficient check.
 *
 * <p>Opt-in twice over: inert unless the caller supplies exit directions -- assuming all six
 * walls open would enforce a rule true of no real vehicle -- and inert unless two distinct
 * stops are in play. The blocker set is strictly later stops: the order within a stop is free,
 * so {@code >=} would refuse two same-stop pallets standing one behind the other.
 *
 * <p>Per placement list the door table is O(n^2 * d); each candidate then costs O(n * d).
 * Mutable per-solve state, so one instance must not be shared between concurrent solves.
 */
public final class StopAccessibilityConstraint implements PlacementConstraint {
    /** An absent stop rides the whole route: never removed, so it blocks every stop. */
    private static final long NO_STOP = Long.MAX_VALUE;

    private final List<String> directions;
    private List<Placement> placements;
    private Dimensions container;
    private List<List<String>> clear = List.of();
    private long[] stops = new long[0];

    public StopAccessibilityConstraint() {
        this(List.of());
    }

    public StopAccessibilityConstraint(List<String> requested) {
        for (String direction : requested) {
            if (!SweptRegion.ALL_DIRECTIONS.contains(direction)) {
                // InvalidDirectionError belongs to the sequence package, which sits above this
                // one and must not be imported downwards. The refusal is the same; only the type
                // differs, and it is programmer error either way.
                throw new IllegalArgumentException("unknown movement direction " + direction);
            }
        }
        // Deduplicated in the canonical order rather than as given: two callers passing the
        // same doors in different orders must search identically.
        this.directions = SweptRegion.ALL_DIRECTIONS.stream().filter(requested::contains).toList();
    }

    private static long stopOf(Placement placement) {
        return placement.instance().item().stopIndex().orElse(NO_STOP);
    }

    /**
     * Per placed box, the doors still open to it against the already-placed boxes. Keyed on the
     * container too, because a corridor runs to a <em>wall</em>: the same boxes have different
     * exits in a longer container.
     */
    private void baseFor(List<Placement> current, Dimensions inner) {
        if (placements != null && ConstraintContext.samePlacements(placements, current) && inner.equals(container)) {
            return;
        }
        long[] nextStops = new long[current.size()];
        List<AxisAlignedBox> boxes = new ArrayList<>(current.size());
        for (int index = 0; index < current.size(); index++) {
            nextStops[index] = stopOf(current.get(index));
            boxes.add(current.get(index).envelopeBox());
        }
        List<List<String>> nextClear = new ArrayList<>(boxes.size());
        for (int index = 0; index < boxes.size(); index++) {
            if (nextStops[index] == NO_STOP) {
                // Never unloaded, so it needs no door of its own -- it only ever blocks.
                nextClear.add(directions);
                continue;
            }
            List<String> open = new ArrayList<>();
            for (String direction : directions) {
                long[] sweep = SweptRegion.volume(boxes.get(index), inner, direction);
                boolean blocked = false;
                for (int other = 0; other < boxes.size(); other++) {
                    if (other != index && nextStops[other] > nextStops[index]
                            && SweptRegion.intersects(sweep, boxes.get(other))) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    open.add(direction);
                }
            }
            nextClear.add(List.copyOf(open));
        }
        placements = current;
        container = inner;
        clear = nextClear;
        stops = nextStops;
    }

    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (directions.isEmpty()) {
            return ConstraintResult.allow();
        }
        if (!context.routeSensitive()) {
            return ConstraintResult.allow();
        }
        long candidateStop = context.item().item().stopIndex().orElse(NO_STOP);
        Dimensions inner = context.container().innerDimensions();
        baseFor(context.placements(), inner);

        // One distinct stop means nothing can be due before anything else, so no corridor can
        // be blocked by a later item. Checked over the candidate too, or the first placement
        // into an empty container would skip a check it should make.
        Set<Long> distinct = new HashSet<>();
        for (long stop : stops) {
            distinct.add(stop);
        }
        distinct.add(candidateStop);
        if (distinct.size() < 2) {
            return ConstraintResult.allow();
        }

        AxisAlignedBox candidate = context.envelopeBox();
        List<Placement> placed = context.placements();
        for (int index = 0; index < placed.size(); index++) {
            if (!(candidateStop > stops[index]) || clear.get(index).isEmpty()) {
                continue;
            }
            Placement placement = placed.get(index);
            boolean stillOpen = false;
            for (String direction : clear.get(index)) {
                if (!SweptRegion.intersects(SweptRegion.volume(placement.envelopeBox(), inner, direction), candidate)) {
                    stillOpen = true;
                    break;
                }
            }
            if (!stillOpen) {
                return ConstraintResult.reject("stop_accessibility_violation", placement.instance().id()
                        + " due at stop " + stops[index] + " loses its last exit to " + context.item().id());
            }
        }

        if (candidateStop == NO_STOP) {
            return ConstraintResult.allow();
        }
        for (String direction : directions) {
            long[] sweep = SweptRegion.volume(candidate, inner, direction);
            boolean blocked = false;
            for (int other = 0; other < placed.size(); other++) {
                if (stops[other] > candidateStop && SweptRegion.intersects(sweep, placed.get(other).envelopeBox())) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return ConstraintResult.allow();
            }
        }
        return ConstraintResult.reject("stop_accessibility_violation",
                context.item().id() + " due at stop " + candidateStop + " would have no exit");
    }
}
