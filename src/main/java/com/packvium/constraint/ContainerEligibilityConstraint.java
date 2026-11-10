package com.packvium.constraint;

import com.packvium.domain.Item;
import java.util.Collections;

/**
 * Rejects a placement into a container the item did not opt into. An item with no
 * {@code eligibleContainerTags} may go anywhere, matching the same "empty means
 * unconstrained" convention as tags/incompatibleTags.
 */
public final class ContainerEligibilityConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        Item item = context.item().item();
        if (item.eligibleContainerTags().isEmpty()) {
            return ConstraintResult.allow();
        }
        if (!Collections.disjoint(item.eligibleContainerTags(), context.container().tags())) {
            return ConstraintResult.allow();
        }
        return ConstraintResult.reject("container_ineligible", context.container().id());
    }
}
