package com.packvium.locks;

import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Refuses a locked type's candidates while that type still owes a locked slot.
 *
 * <p>Deliberately one-directional. It can say <em>no</em> and nothing else: it never runs instead
 * of the other constraints, only alongside them, and every constraint must allow a candidate for
 * it to be placed. So no branch here can admit a placement the engine would have refused.
 *
 * <p>A lock reserves; it does not demand priority. While a lock is outstanding, its box is off
 * limits to every other candidate of that type; the candidate that <em>is</em> the lock is
 * admitted, and every candidate that does not touch the reserved volume is untouched. Locking one
 * of eight identical cubes therefore leaves the other seven free, and a lock on the far corner
 * still holds although an empty container offers only the origin as a candidate point.
 *
 * <p>A constraint cannot know which container index it is packing -- the index is a position in
 * the result, which does not exist until the solve finishes -- so a lock applies in every
 * container its box physically fits, and {@link Locks#resolveWithLocks} verifies preservation at
 * the exact index afterwards. Over-applying can only forbid, never place.
 *
 * <p>Complexity: O(L) per candidate for L locks naming the item's type, plus one O(P) walk of the
 * container's placements when such a lock exists -- the walk the support and overlap rules already
 * make, so the per-candidate bound of the candidate search is unchanged. A request
 * with no locks never constructs this constraint.
 */
final class LockConstraint implements PlacementConstraint {
    private final List<PlacementLock> locks;

    LockConstraint(List<PlacementLock> locks) {
        this.locks = List.copyOf(locks);
    }

    List<PlacementLock> locks() {
        return locks;
    }

    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        String itemType = context.item().item().id();
        List<PlacementLock> relevant = new ArrayList<>();
        for (PlacementLock lock : locks) {
            if (lock.itemType().equals(itemType) && applicable(lock, context)) {
                relevant.add(lock);
            }
        }
        if (relevant.isEmpty()) {
            return ConstraintResult.allow();
        }

        Set<PlacementLock.Slot> filled = new HashSet<>();
        for (Placement placement : context.placements()) {
            if (placement.instance().item().id().equals(itemType)) {
                filled.add(slot(placement.rotation().name(), placement.position()));
            }
        }
        PlacementLock.Slot candidateSlot = slot(context.rotation().name(), context.point());
        for (PlacementLock lock : relevant) {
            if (filled.contains(lock.slot()) || lock.slot().equals(candidateSlot)) {
                continue;
            }
            if (Locks.overlaps(context.point(), context.dimensions(), lock.positionTicks(), reservedBox(lock, context))) {
                return ConstraintResult.reject(Locks.LOCK_VIOLATED, itemType + " is locked to " + lock.describe());
            }
        }
        return ConstraintResult.allow();
    }

    /**
     * Whether this container could hold the locked box at all. Without it a lock read from one
     * container would refuse every candidate of its type in a container too small for the locked
     * origin, and leave the type unpacked there. The physical box, not the clearance envelope: it
     * only drops locks that plainly cannot apply, and one it admits is still judged by the ordinary
     * geometry rules. Written as subtractions so a lock far outside any container cannot overflow.
     */
    private static boolean applicable(PlacementLock lock, ConstraintContext context) {
        Dimensions box = reservedBox(lock, context);
        Dimensions inner = context.container().innerDimensions();
        List<Long> position = lock.positionTicks();
        return fits(position.get(0), box.length().ticks(), inner.length().ticks())
                && fits(position.get(1), box.width().ticks(), inner.width().ticks())
                && fits(position.get(2), box.height().ticks(), inner.height().ticks());
    }

    private static boolean fits(long origin, long extent, long room) {
        return extent <= room && origin <= room - extent;
    }

    /**
     * The volume the locked item will occupy, sized from its own type. The physical box: a
     * clearance is a margin the ordinary clearance rule already enforces around whatever ends up
     * here, and reserving it twice would refuse neighbours the engine is willing to place.
     */
    private static Dimensions reservedBox(PlacementLock lock, ConstraintContext context) {
        return context.item().item().dimensions().rotated(lock.rotation());
    }

    private static PlacementLock.Slot slot(String orientation, Point point) {
        return new PlacementLock.Slot(orientation, List.of(point.x(), point.y(), point.z()));
    }
}
