package com.packvium.locks;

import com.packvium.domain.Rotation;
import com.packvium.support.PythonRepr;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One operator lock: an item type pinned to an exact position and orientation.
 *
 * <p>Addressed the way the execution plan addresses a placement -- container index, item type,
 * exact integer position ticks and orientation -- and never by item id. An id is an instance
 * counter engines need not agree on, and a lock that meant different boxes in different runtimes
 * would be worse than no lock.
 *
 * @param containerIndex the container's position in the result's container list
 * @param itemType the item type's id, as the request names it
 * @param orientation the rotation's name, such as {@code LWH}
 * @param positionTicks the corner position, x then y then z, in length ticks
 */
public record PlacementLock(int containerIndex, String itemType, String orientation, List<Long> positionTicks) {
    /** Refuses an address the engine could not have emitted. */
    public PlacementLock {
        if (containerIndex < 0) {
            throw new IllegalArgumentException("a lock must name a container index");
        }
        if (itemType == null || itemType.isEmpty()) {
            throw new IllegalArgumentException("a lock must name an item type");
        }
        if (Arrays.stream(Rotation.values()).noneMatch(rotation -> rotation.name().equals(orientation))) {
            throw new IllegalArgumentException(
                    PythonRepr.string(String.valueOf(orientation)) + " is not an orientation this engine emits");
        }
        if (positionTicks.size() != 3) {
            throw new IllegalArgumentException("a lock's position must have three axes");
        }
        // A copy that tolerates null, so a missing axis earns this message rather than List.copyOf's.
        List<Long> ticks = Collections.unmodifiableList(new ArrayList<>(positionTicks));
        if (ticks.contains(null)) {
            throw new IllegalArgumentException("a lock's position must be exact integer ticks");
        }
        if (ticks.stream().anyMatch(tick -> tick < 0)) {
            throw new IllegalArgumentException("a lock's position cannot be negative");
        }
        positionTicks = ticks;
    }

    /** A lock at {@code (x, y, z)} ticks. */
    public PlacementLock(int containerIndex, String itemType, String orientation, long x, long y, long z) {
        this(containerIndex, itemType, orientation, List.of(x, y, z));
    }

    /** The orientation and position together: what a lock reserves inside its item type. */
    public Slot slot() {
        return new Slot(orientation, positionTicks);
    }

    /** The rotation this lock names. */
    Rotation rotation() {
        return Rotation.valueOf(orientation);
    }

    /** {@code LWH@(0, 0, 0)}, the Python engine's spelling of a lock in its diagnostics. */
    String describe() {
        return orientation + "@" + PythonRepr.integers(positionTicks);
    }

    /**
     * A placement slot: an orientation at a position.
     *
     * @param orientation the rotation's name
     * @param positionTicks x, y and z in length ticks
     */
    public record Slot(String orientation, List<Long> positionTicks) {
        /** Copies the position. */
        public Slot {
            positionTicks = List.copyOf(positionTicks);
        }

        /** {@code ('LWH', (0, 0, 0))}, the Python engine's spelling of a slot. */
        String describe() {
            return "(" + PythonRepr.string(orientation) + ", " + PythonRepr.integers(positionTicks) + ")";
        }
    }
}
