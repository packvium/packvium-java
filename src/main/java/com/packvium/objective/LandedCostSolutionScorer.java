package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.RateTable;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Ranks by the money a shipment actually costs, not by the grams it is billed at.
 *
 * <p>A bracket step or a minimum charge means the cheaper answer is not always the lighter
 * one. It extends {@link ShippingCostSolutionScorer} so the billed weight is that scorer's:
 * the two objectives must never disagree about what is being priced. A billed weight past
 * the last bracket depends on how the search filled the box, so it loses the candidate
 * ({@link #UNPRICEABLE_MINOR}) instead of aborting a run with a shippable alternative; the
 * packer refuses if that sentinel still wins.
 */
public final class LandedCostSolutionScorer extends ShippingCostSolutionScorer {
    public static final String OBJECTIVE_NAME = "lowest_landed_cost";

    /** A search device, never an answer; the same sentinel as the other engines. */
    public static final long UNPRICEABLE_MINOR = Long.MAX_VALUE;

    public LandedCostSolutionScorer(long divisor, String lengthUnit, String weightUnit) {
        super(divisor, lengthUnit, weightUnit);
    }

    public static LandedCostSolutionScorer fromConfig(PackingConfig config) {
        long divisor = requireDivisor(config, OBJECTIVE_NAME);
        return new LandedCostSolutionScorer(divisor, config.dimensionalWeightLengthUnit(),
                config.dimensionalWeightWeightUnit());
    }

    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        long landed = 0;
        for (PackedContainer packed : solution.containers()) {
            packed.container().rateTable().orElseThrow(() -> missingTable(packed));
            long charge = chargeOrUnpriceable(packed.container(), billedTicks(packed));
            if (charge == UNPRICEABLE_MINOR) {
                landed = UNPRICEABLE_MINOR;
                break;
            }
            landed = Math.addExact(landed, charge);
        }
        return ObjectiveScore.of(base.component(0), landed, base.component(1), base.component(3),
                base.component(4));
    }

    /** The first container of a finished answer its tariff cannot price, if any. */
    public static Optional<Unpriceable> unpriceableContainer(List<PackedContainer> containers, PackingConfig config) {
        if (!config.objective().equals(OBJECTIVE_NAME) || config.dimensionalWeightDivisor().isEmpty()) {
            return Optional.empty();
        }
        LandedCostSolutionScorer scorer = fromConfig(config);
        for (PackedContainer packed : containers) {
            long grams = grams(scorer.billedTicks(packed));
            Optional<RateTable> table = packed.container().rateTable();
            if (table.isEmpty()) {
                return Optional.of(new Unpriceable(packed.container().id(), grams, 0));
            }
            if (table.get().chargeMinorOrEmpty(grams).isEmpty()) {
                return Optional.of(new Unpriceable(packed.container().id(), grams, table.get().lastBracketG()));
            }
        }
        return Optional.empty();
    }

    /**
     * The landed charge at this billed weight, or {@link #UNPRICEABLE_MINOR} when the container
     * has no tariff or its tariff does not reach that weight.
     */
    public static long chargeOrUnpriceable(Container container, long billedTicks) {
        OptionalLong charge = container.rateTable().map(table -> table.chargeMinorOrEmpty(grams(billedTicks)))
                .orElse(OptionalLong.empty());
        return charge.isPresent() ? charge.getAsLong() : UNPRICEABLE_MINOR;
    }

    /**
     * Billed weight in whole grams, rounded up: a shipment fractionally over a bracket is in
     * the next bracket, as on a carrier's scale.
     */
    public static long grams(long weightTicks) {
        return Math.addExact(weightTicks, Weight.TICKS_PER_G - 1) / Weight.TICKS_PER_G;
    }

    private static UnknownObjectiveException missingTable(PackedContainer packed) {
        return new UnknownObjectiveException("the lowest_landed_cost objective requires a rate_table on every "
                + "container; '" + packed.container().id() + "' has none");
    }

    /** A container whose billed weight lies above its tariff's last bracket. */
    public record Unpriceable(String containerId, long billedGrams, long lastBracketG) {
    }
}
