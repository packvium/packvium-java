package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import java.util.List;

/**
 * One candidate placement and the state it is judged against.
 *
 * <p>{@code stackSensitive} is false only when nothing in the container can refuse or be
 * crushed by a load, which lets the bearing check skip a per-candidate walk of the whole
 * stack. {@code routeSensitive} is false when no item in play declares a {@code stopIndex}.
 * {@code placements} is copied with {@link List#copyOf}, which returns an already immutable
 * list unchanged, so the per-state caches in this package can recognise it by identity.
 */
public record ConstraintContext(
        Container container,
        List<Placement> placements,
        ItemInstance item,
        Point point,
        Rotation rotation,
        Dimensions dimensions,
        Dimensions envelopeDimensions,
        boolean stackSensitive,
        boolean routeSensitive) {

    public ConstraintContext {
        placements = List.copyOf(placements);
    }

    public ConstraintContext(Container container, List<Placement> placements, ItemInstance item, Point point,
            Rotation rotation, Dimensions dimensions, Dimensions envelopeDimensions) {
        this(container, placements, item, point, rotation, dimensions, envelopeDimensions, true, true);
    }

    public AxisAlignedBox envelopeBox() {
        return new AxisAlignedBox(point, envelopeDimensions);
    }

    /** The reference engine's {@code ===} on two placement lists: same elements, same order. */
    static boolean samePlacements(List<Placement> left, List<Placement> right) {
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
}
