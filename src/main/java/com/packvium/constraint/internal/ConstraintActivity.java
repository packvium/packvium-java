package com.packvium.constraint.internal;

import com.packvium.constraint.AxleLoadConstraint;
import com.packvium.constraint.CompatibilityConstraint;
import com.packvium.constraint.ContainerEligibilityConstraint;
import com.packvium.constraint.FloorConstraint;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.RouteOrderConstraint;
import com.packvium.constraint.StopAccessibilityConstraint;
import com.packvium.constraint.TagCountConstraint;
import com.packvium.constraint.TopLoadConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import java.util.Collections;

/**
 * Sweep-level activity specification for the built-in constraint chain.
 *
 * <p>It deliberately knows only the built-ins ConstraintSet creates. A registered custom
 * constraint never matches and is therefore always evaluated. Keeping the specification
 * internal avoids adding an optimisation-only method to the public constraint interface.
 * O(t) for t item tags.
 */
public final class ConstraintActivity {
    private ConstraintActivity() {
    }

    /** Whether the constraint allows every position of this item in this container. */
    public static boolean isInert(PlacementConstraint constraint, Container container, ItemInstance instance,
            boolean stackSensitive, boolean routeSensitive) {
        Item item = instance.item();
        if (constraint instanceof FloorConstraint) {
            return !item.mustBeOnFloor();
        }
        if (constraint instanceof ContainerEligibilityConstraint) {
            return item.eligibleContainerTags().isEmpty()
                    || !Collections.disjoint(item.eligibleContainerTags(), container.tags());
        }
        if (constraint instanceof CompatibilityConstraint) {
            return item.tags().isEmpty() && item.incompatibleTags().isEmpty();
        }
        if (constraint instanceof TagCountConstraint) {
            return container.tagLimits().isEmpty()
                    || Collections.disjoint(item.tags(), container.tagLimits().keySet());
        }
        if (constraint instanceof TopLoadConstraint) {
            return !stackSensitive;
        }
        if (constraint instanceof RouteOrderConstraint) {
            return !routeSensitive;
        }
        if (constraint instanceof StopAccessibilityConstraint stop) {
            // The constraint carries its own default doors, where the reference reads them from
            // the configuration: here they are the constructor argument it was built with.
            return !routeSensitive
                    || (container.accessDirections().isEmpty() && stop.defaultDirections().isEmpty());
        }
        if (constraint instanceof AxleLoadConstraint) {
            return container.axles().isEmpty();
        }
        return false;
    }
}
