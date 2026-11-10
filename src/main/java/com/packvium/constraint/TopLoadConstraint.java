package com.packvium.constraint;

import com.packvium.constraint.internal.LoadAnalysis;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Item;
import com.packvium.domain.Placement;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rejects a placement that would rest on something unable to carry it.
 *
 * <p>Bearing limits are checked against the cumulative load of the whole stack, not only the
 * box directly underneath, so a tower of light items cannot crush its base.
 *
 * <p>The support graph over the <em>placed</em> boxes is the same for every candidate evaluated
 * against one search state, and rebuilding it per candidate was the cost removes. One
 * base per placement list is kept here and each candidate is appended to it. The cache is
 * deliberately a single entry: search evaluates a run of candidates against one state before
 * moving on. Per candidate: O(n) for the delta graph plus O(n log n + E) for the load vector.
 * Mutable per-solve state, so one instance must not be shared between concurrent solves.
 */
public final class TopLoadConstraint implements PlacementConstraint {
    private List<Placement> placements;
    private LoadSupportGraph base;
    private List<LoadUnit> baseUnits = List.of();
    private long hint = 1;

    /**
     * The support graph over {@code placements} alone, rebuilt only when it cannot serve. The
     * cell hint has to cover every candidate appended to this base, and the widest is not known
     * in advance, so it grows to fit the first candidate that needs it and the base is rebuilt
     * that once. Sizing it from the container instead would always be safe and always coarse.
     */
    private LoadSupportGraph baseFor(List<Placement> current, long footprint) {
        boolean same = base != null && ConstraintContext.samePlacements(placements, current);
        if (same && footprint <= hint) {
            return base;
        }
        hint = Math.max(same ? hint : 1, footprint);
        placements = current;
        baseUnits = List.copyOf(LoadCalculator.units(current));
        base = new LoadSupportGraph(baseUnits, hint);
        return base;
    }

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
        long footprint = Math.max(candidate.x2() - candidate.origin().x(), candidate.y2() - candidate.origin().y());
        LoadSupportGraph graph = baseFor(context.placements(), footprint).withUnit(unit, hint);
        List<LoadUnit> units = new ArrayList<>(baseUnits.size() + 1);
        units.addAll(baseUnits);
        units.add(unit);
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
