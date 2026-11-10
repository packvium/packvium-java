package com.packvium.constraint;

import com.packvium.domain.Placement;
import java.util.Map;

/**
 * Caps how many items carrying a tag may share one container.
 *
 * <p>A group is atomic but cannot express a count: "at most two of this class per container"
 * is a policy about the container, not about any one item's neighbours, so it lives on the
 * container's tag limits rather than on the item. O(t * n) for t limited tags.
 */
public final class TagCountConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        Map<String, Long> limits = context.container().tagLimits();
        if (limits.isEmpty()) {
            return ConstraintResult.allow();
        }
        for (String tag : context.item().item().tags()) {
            Long limit = limits.get(tag);
            if (limit == null) {
                continue;
            }
            long count = 0;
            for (Placement placement : context.placements()) {
                if (placement.instance().item().tags().contains(tag)) {
                    count++;
                }
            }
            if (count + 1 > limit) {
                return ConstraintResult.reject("tag_count_exceeded",
                        tag + ": limit " + limit + ", would be " + (count + 1));
            }
        }
        return ConstraintResult.allow();
    }
}
