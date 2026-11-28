package com.packvium.constraint;

import com.packvium.constraint.internal.LoadAnalysis;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.constraint.internal.PlacedSupportBase;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Item;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.Optional;

/**
 * Rejects a placement that would rest on something unable to carry it.
 *
 * <p>Bearing limits are checked against the cumulative load of the whole stack, not only the
 * box directly underneath, so a tower of light items cannot crush its base.
 *
 * <p>The graph comes from a {@link PlacedSupportBase}, so it is not rebuilt per candidate. Per
 * candidate: O(n) for the delta graph plus O(n log n + E) for the load vector. Mutable per-solve
 * state, so one instance must not be shared between concurrent solves.
 */
public final class TopLoadConstraint implements PlacementConstraint {
    private final PlacedSupportBase support = new PlacedSupportBase();

    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (!context.stackSensitive()) {
            return ConstraintResult.allow();
        }
        AxisAlignedBox candidate = context.envelopeBox();
        Item item = context.item().item();
        LoadUnit unit = new LoadUnit(candidate, context.item().weight().ticks(), item.maxTopLoad().map(Weight::ticks),
                item.maxStackedItems(), context.item().id(),
                item.nestingHeight().isPresent() ? Optional.of(item.id()) : Optional.empty(),
                item.nestingHeight().map(Length::ticks), item.compressionRatioPpm(), item.maxCompressionPressureKpa());
        PlacedSupportBase.Extended extended = support.withCandidate(context.placements(), unit);
        List<LoadUnit> units = extended.units();
        LoadSupportGraph graph = extended.graph();
        Optional<Violation> nonStackable = graph.nonStackableFailure(context.placements(), context.item(),
                units.size() - 1);
        if (nonStackable.isPresent()) {
            return ConstraintResult.of(nonStackable);
        }
        Optional<Long> densityLimit = context.container().maxStackDensity().map(Weight::ticks);
        LoadAnalysis analysis = new LoadAnalysis(units, graph);
        Optional<Violation> failure = analysis.overloaded()
                .or(analysis::crushed)
                .or(analysis::stackLimitExceeded)
                .or(() -> analysis.stackDensityExceeded(densityLimit));
        return ConstraintResult.of(failure);
    }
}
