package com.packvium.constraint;

import com.packvium.domain.Item;
import com.packvium.domain.Placement;
import com.packvium.unit.Length;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rejects a placement that would bury an item due off at an earlier stop.
 *
 * <p>A no-op unless some item declares a {@code stopIndex}: route enforcement is opt-in, so a
 * request that never mentions a route sees no new rejection code and no behaviour change
 *. O(n log n + E) per candidate for the graph, O(n^2) worst case for the
 * ancestor sets.
 */
public final class RouteOrderConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (!context.routeSensitive()) {
            return ConstraintResult.allow();
        }
        Item item = context.item().item();
        List<LoadUnit> units = LoadCalculator.units(context.placements(), new LoadUnit(context.envelopeBox(),
                context.item().weight().ticks(), Optional.empty(), Optional.empty(), context.item().id(),
                item.nestingHeight().isPresent() ? Optional.of(item.id()) : Optional.empty(),
                item.nestingHeight().map(Length::ticks)));
        List<Double> stops = new ArrayList<>(context.placements().size() + 1);
        for (Placement placement : context.placements()) {
            stops.add(stopOf(placement.instance().item()));
        }
        stops.add(stopOf(item));
        return ConstraintResult.of(LoadCalculator.routeOrderViolated(units, stops));
    }

    /** Exact: stop indices are bounded to 2^53 - 1, which a double carries without loss. */
    private static double stopOf(Item item) {
        return item.stopIndex().map(Long::doubleValue).orElse(Double.POSITIVE_INFINITY);
    }
}
