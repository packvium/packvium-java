package com.packvium.locks;

import java.util.List;
import java.util.Map;

/**
 * A re-solve under a lock set, beside the plan it came from -- never replacing it.
 *
 * <p>{@code preserved} is measured against {@code result}, not promised by the constraint:
 * forbidding alternatives stops the solver succeeding elsewhere, it does not make it try the
 * locked point. {@code missing} names the locks the result does not contain, which is what a
 * caller shows an operator whose lock could not be honoured.
 *
 * @param locks the lock set, in the order given
 * @param result the locked solve's result, as the wire format spells it
 * @param preserved whether every lock is in {@code result} exactly
 * @param missing the locks {@code result} does not contain, in the order given
 */
public record LockedResolve(List<PlacementLock> locks, Map<String, Object> result, boolean preserved,
        List<PlacementLock> missing) {
    /** Refuses a preserved resolve that is missing a lock, and an unpreserved one that names none. */
    public LockedResolve {
        locks = List.copyOf(locks);
        missing = List.copyOf(missing);
        if (preserved && !missing.isEmpty()) {
            throw new IllegalArgumentException("a preserved resolve cannot be missing a lock");
        }
        if (!preserved && missing.isEmpty()) {
            throw new IllegalArgumentException("an unpreserved resolve must name the locks it could not honour");
        }
    }
}
