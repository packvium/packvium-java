package com.packvium.constraint;

import com.packvium.domain.Item;
import com.packvium.domain.Placement;
import java.util.Collections;

/** O(n * t^2) for n placements carrying t tags each. */
public final class CompatibilityConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        Item item = context.item().item();
        for (Placement placement : context.placements()) {
            Item other = placement.instance().item();
            if (!Collections.disjoint(item.incompatibleTags(), other.tags())
                    || !Collections.disjoint(other.incompatibleTags(), item.tags())) {
                return ConstraintResult.reject("incompatible_items", item.id() + " is incompatible with " + other.id());
            }
        }
        return ConstraintResult.allow();
    }
}
