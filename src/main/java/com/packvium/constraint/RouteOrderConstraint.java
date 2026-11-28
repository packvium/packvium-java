package com.packvium.constraint;

import com.packvium.constraint.internal.LoadAnalysis;
import com.packvium.constraint.internal.PlacedSupportBase;
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
 * request that never mentions a route sees no new rejection code and no behaviour change.
 * The graph comes from a {@link PlacedSupportBase}, O(n) per candidate instead of a rebuild;
 * O(n^2) worst case for the ancestor sets. Mutable per-solve state, so one instance must not be
 * shared between concurrent solves.
 */
public final class RouteOrderConstraint implements PlacementConstraint {
    private final PlacedSupportBase support = new PlacedSupportBase();

    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (!context.routeSensitive()) {
            return ConstraintResult.allow();
        }
        Item item = context.item().item();
        LoadUnit candidate = new LoadUnit(context.envelopeBox(), context.item().weight().ticks(), Optional.empty(),
                Optional.empty(), context.item().id(),
                item.nestingHeight().isPresent() ? Optional.of(item.id()) : Optional.empty(),
                item.nestingHeight().map(Length::ticks));
        List<Double> stops = new ArrayList<>(context.placements().size() + 1);
        for (Placement placement : context.placements()) {
            stops.add(stopOf(placement.instance().item()));
        }
        stops.add(stopOf(item));
        if (!LoadAnalysis.routed(stops)) {
            return ConstraintResult.allow();
        }
        PlacedSupportBase.Extended extended = support.withCandidate(context.placements(), candidate);
        return ConstraintResult.of(new LoadAnalysis(extended.units(), extended.graph()).routeOrderViolated(stops));
    }

    /** Exact: stop indices are bounded to 2^53 - 1, which a double carries without loss. */
    private static double stopOf(Item item) {
        return item.stopIndex().map(Long::doubleValue).orElse(Double.POSITIVE_INFINITY);
    }
}
