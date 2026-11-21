package com.packvium.locks;

import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.Point;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.serialization.RequestDecoder;
import com.packvium.serialization.RequestPacker;
import com.packvium.support.PythonRepr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator locks, expressed as request-derived constraints.
 *
 * <p>A lock is an operator saying <em>this item stays in this box, in this place</em>, and the
 * whole design problem is that honouring one must not become a way around the solver or the
 * validator. It is not: a lock is an ordinary {@link PlacementConstraint} supplied through the
 * existing {@link ExtensionRegistry}, and everything after that is the ordinary path -- the same
 * portfolio, the same constraints, the same independent validation. There is no lock-aware
 * solver, no relaxed validator and no special case anywhere in the engine, which is why a lock
 * cannot produce a placement the engine would otherwise refuse. The strongest thing it can do is
 * forbid alternatives to itself.
 *
 * <p>Four properties follow.
 * <ul>
 *   <li><strong>The approved plan is never changed.</strong> {@link #resolveWithLocks} returns a
 *       {@link LockedResolve} carrying a <em>separate</em> result, so "what was approved" and
 *       "what was proposed after the lock" are two artifacts.</li>
 *   <li><strong>A physically invalid lock is refused, not accommodated.</strong> Support, top
 *       load, overlap and route order still run, so a lock that would float an item leaves it
 *       unplaced, and the result says so in the engine's own vocabulary.</li>
 *   <li><strong>Infeasibility is an answer, not an exception.</strong> A lock the solve cannot
 *       satisfy comes back as {@code preserved == false} with the ordinary unpacked-item proof.
 *       Only a lock set that contradicts itself is refused, with {@link LockSetError}, before any
 *       solve.</li>
 *   <li><strong>Preservation is verified, not assumed.</strong> It is checked against the
 *       returned result and reported.</li>
 * </ul>
 *
 * <p>Locking has a cost, and it is not in this package: registering any caller constraint takes a
 * request off the grid solver, which places by formula and never evaluates the constraint chain.
 * A grid solver that ignored a lock would be exactly the bypass this design forbids.
 *
 * <p>This package is a caller of the packer and never a part of it: nothing under
 * {@code com.packvium.algorithm}, {@code constraint} or {@code validation} imports it.
 */
public final class Locks {
    /**
     * The rejection code a locked item's non-locked candidate carries. Named like every other
     * constraint code, so a trace or an unpacked item's details read the same way whether the
     * refusal came from physics or from an operator.
     */
    public static final String LOCK_VIOLATED = "operator_lock";

    private Locks() {
    }

    /**
     * Read locks out of an execution plan's own placement references.
     *
     * <p>The plan is where an operator sees the placements, so it is where they point at one.
     * Reading the references it already emits, rather than a second address format, is the only
     * reason the two can be trusted to mean the same box. O(s) in the plan's steps.
     *
     * @param plan a plan from {@link com.packvium.execution.Plan#build}, or the same tree decoded from JSON
     * @param containerIndex the container whose placements to lock
     * @param itemTypes the item types to lock; others are left free
     * @return one lock per matching step, in step order
     */
    public static List<PlacementLock> locksFromPlan(Map<String, ?> plan, int containerIndex,
            Collection<String> itemTypes) {
        Set<String> wanted = new HashSet<>(itemTypes);
        List<PlacementLock> locks = new ArrayList<>();
        for (Object container : list(plan.get("containers"))) {
            Map<?, ?> entry = (Map<?, ?>) container;
            if (!(entry.get("container_index") instanceof Number index) || index.longValue() != containerIndex) {
                continue;
            }
            for (Object step : list(entry.get("steps"))) {
                Object placement = ((Map<?, ?>) step).get("placement");
                Map<?, ?> reference = placement instanceof Map<?, ?> map ? map : Map.of();
                if (!wanted.contains(reference.get("item_type"))) {
                    continue;
                }
                Map<?, ?> ticks = reference.get("position_ticks") instanceof Map<?, ?> map ? map : Map.of();
                locks.add(new PlacementLock(containerIndex, String.valueOf(reference.get("item_type")),
                        String.valueOf(reference.get("orientation")),
                        tick(ticks, "x"), tick(ticks, "y"), tick(ticks, "z")));
            }
        }
        return List.copyOf(locks);
    }

    /**
     * The caller's extensions plus the lock constraint.
     *
     * <p>A registry rather than a new solver argument, because that is the extension point the
     * engine already has, and using it means the locked run is the ordinary run. Everything in
     * {@code base} is kept, so an application's own constraints are not silently dropped by
     * locking; the lock constraint runs after them.
     *
     * @return {@code base} itself when there are no locks
     */
    public static ExtensionRegistry lockRegistry(List<PlacementLock> locks, ExtensionRegistry base) {
        if (locks.isEmpty()) {
            return base;
        }
        List<PlacementConstraint> constraints = new ArrayList<>(base.placementConstraints());
        constraints.add(new LockConstraint(locks));
        return new ExtensionRegistry(constraints, base.itemOrderStrategies(), base.solvers(), base.candidateScorer(),
                base.containerSelector());
    }

    /** {@link #lockRegistry(List, ExtensionRegistry)} over no caller extensions. */
    public static ExtensionRegistry lockRegistry(List<PlacementLock> locks) {
        return lockRegistry(locks, ExtensionRegistry.none());
    }

    /** {@link #resolveWithLocks(Map, List, ExtensionRegistry)} with no caller extensions. */
    public static LockedResolve resolveWithLocks(Map<String, ?> request, List<PlacementLock> locks) {
        return resolveWithLocks(request, locks, ExtensionRegistry.none());
    }

    /**
     * Re-solve {@code request} with {@code locks} applied, through the ordinary packing path.
     *
     * <p>The request is read, never written. The lock set is checked against the request's items
     * before anything is solved, in O(L²) over L locks -- cheap beside the solve, which is the
     * expensive step. Then the request is packed once through {@link RequestPacker#pack(Object,
     * ExtensionRegistry)}, with its own policy and {@code base}'s constraints still in force, and
     * every lock is looked up among the result's placements in O(p + L).
     *
     * @param request the request as {@link com.packvium.serialization.JsonReader} decodes it
     * @param base the caller's own extensions, kept alongside the locks
     * @throws LockSetError if a lock names an item type the request lacks, the same placement is
     *     locked twice, or two locks in one container claim overlapping space
     */
    public static LockedResolve resolveWithLocks(Map<String, ?> request, List<PlacementLock> locks,
            ExtensionRegistry base) {
        List<PlacementLock> lockSet = List.copyOf(locks);
        refuseAContradictorySet(lockSet, RequestDecoder.itemsById(request));
        Map<String, Object> result = RequestPacker.pack(request, lockRegistry(lockSet, base));
        Set<PlacedKey> placed = placed(result);
        List<PlacementLock> missing = new ArrayList<>();
        for (PlacementLock lock : lockSet) {
            if (!placed.contains(new PlacedKey(lock.containerIndex(), lock.itemType(), lock.orientation(),
                    lock.positionTicks()))) {
                missing.add(lock);
            }
        }
        return new LockedResolve(lockSet, result, missing.isEmpty(), missing);
    }

    /**
     * Whether a box at {@code origin} meets one at {@code otherOrigin}, extents half-open as
     * {@link com.packvium.domain.AxisAlignedBox#intersects} reads them. Compared as differences of
     * two non-negative coordinates, so a lock anywhere in the 64-bit range cannot overflow.
     */
    static boolean overlaps(Point origin, Dimensions dimensions, List<Long> otherOrigin, Dimensions other) {
        return axisOverlaps(origin.x(), dimensions.length().ticks(), otherOrigin.get(0), other.length().ticks())
                && axisOverlaps(origin.y(), dimensions.width().ticks(), otherOrigin.get(1), other.width().ticks())
                && axisOverlaps(origin.z(), dimensions.height().ticks(), otherOrigin.get(2), other.height().ticks());
    }

    private static boolean axisOverlaps(long start, long extent, long otherStart, long otherExtent) {
        return otherStart - start < extent && start - otherStart < otherExtent;
    }

    /**
     * Every way a lock set can be wrong without a request being solved. Locks in different
     * containers are never compared: they describe different boxes and cannot contradict.
     */
    private static void refuseAContradictorySet(List<PlacementLock> locks, Map<String, Item> items) {
        List<Dimensions> boxes = new ArrayList<>(locks.size());
        for (PlacementLock lock : locks) {
            Item item = items.get(lock.itemType());
            if (item == null) {
                throw new LockSetError(
                        "the request has no item type " + PythonRepr.string(lock.itemType()) + " to lock");
            }
            boxes.add(item.dimensions().rotated(lock.rotation()));
        }
        for (int index = 0; index < locks.size(); index++) {
            PlacementLock lock = locks.get(index);
            for (int otherIndex = index + 1; otherIndex < locks.size(); otherIndex++) {
                PlacementLock other = locks.get(otherIndex);
                if (lock.containerIndex() != other.containerIndex()) {
                    continue;
                }
                if (lock.slot().equals(other.slot()) && lock.itemType().equals(other.itemType())) {
                    throw new LockSetError("the same placement is locked twice: " + lock.slot().describe());
                }
                List<Long> at = lock.positionTicks();
                if (overlaps(new Point(at.get(0), at.get(1), at.get(2)), boxes.get(index), other.positionTicks(),
                        boxes.get(otherIndex))) {
                    throw new LockSetError("two locks claim overlapping space in container " + lock.containerIndex()
                            + ": " + lock.itemType() + " at " + lock.describe() + " and " + other.itemType() + " at "
                            + other.describe());
                }
            }
        }
    }

    /** A placement keyed the way a lock addresses one. */
    private record PlacedKey(int containerIndex, String itemType, String orientation, List<Long> positionTicks) {
    }

    /**
     * Every placement in the result, keyed the way a lock addresses one. The container index is
     * the container's position in the emitted list, the order the execution plan indexes.
     */
    private static Set<PlacedKey> placed(Map<String, Object> result) {
        Set<PlacedKey> placed = new HashSet<>();
        List<?> containers = list(result.get("containers"));
        for (int index = 0; index < containers.size(); index++) {
            for (Object entry : list(((Map<?, ?>) containers.get(index)).get("placements"))) {
                Map<?, ?> placement = (Map<?, ?>) entry;
                if (!(placement.get("position") instanceof Map<?, ?> position)) {
                    continue;
                }
                List<Long> ticks = new ArrayList<>(3);
                for (String axis : List.of("x", "y", "z")) {
                    if (position.get(axis) instanceof Map<?, ?> scalar && scalar.get("ticks") instanceof Number number) {
                        ticks.add(number.longValue());
                    }
                }
                // A placement without integer ticks cannot be the one a lock names.
                if (ticks.size() == 3) {
                    placed.add(new PlacedKey(index, String.valueOf(placement.get("item_type")),
                            String.valueOf(placement.get("orientation")), List.copyOf(ticks)));
                }
            }
        }
        return placed;
    }

    private static long tick(Map<?, ?> ticks, String axis) {
        Object value = ticks.get(axis);
        // A tick past 64 bits is refused, never truncated to a different position.
        if (!(value instanceof Number number) || value instanceof BigInteger big && big.bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException("a lock's position must be exact integer ticks");
        }
        return number.longValue();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
