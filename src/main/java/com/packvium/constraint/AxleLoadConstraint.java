package com.packvium.constraint;

import com.packvium.domain.Item;
import com.packvium.unit.Length;
import java.util.Optional;

/**
 * Rejects a placement that would push either of a two-axle container's axles over its own
 * limit. A no-op unless the container declares axles, so a caller who never
 * configured them sees no new rejection code and no behaviour change. O(n) per candidate.
 */
public final class AxleLoadConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (context.container().axles().isEmpty()) {
            return ConstraintResult.allow();
        }
        Item item = context.item().item();
        LoadUnit candidate = new LoadUnit(context.envelopeBox(), context.item().weight().ticks(), Optional.empty(),
                Optional.empty(), context.item().id(),
                item.nestingHeight().isPresent() ? Optional.of(item.id()) : Optional.empty(),
                item.nestingHeight().map(Length::ticks));
        return ConstraintResult.of(AxleLoad.exceeded(context.container().axles().get(),
                LoadCalculator.units(context.placements(), candidate), context.container().tareWeight().ticks(),
                context.container().innerDimensions().length().ticks()));
    }
}
