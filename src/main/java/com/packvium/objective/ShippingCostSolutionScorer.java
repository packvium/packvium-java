package com.packvium.objective;

import com.packvium.algorithm.RawSolution;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.PackedContainer;
import java.util.Optional;

/**
 * Ranks by total carrier-billable weight ahead of container count, unused volume and stack
 * height.
 *
 * <p>Each container bills at max(gross weight, dimensional weight), the dimensional weight
 * taken from the outer dimensions when declared: a carrier measures the box that moves. There
 * is no library-chosen divisor; a wrong guess would silently misprice every shipment.
 */
public class ShippingCostSolutionScorer implements SolutionScorer {
    protected final long divisor;
    protected final String lengthUnit;
    protected final String weightUnit;

    public ShippingCostSolutionScorer(long divisor, String lengthUnit, String weightUnit) {
        this.divisor = divisor;
        this.lengthUnit = lengthUnit;
        this.weightUnit = weightUnit;
    }

    public static ShippingCostSolutionScorer fromConfig(PackingConfig config) {
        long divisor = requireDivisor(config, "shipping_cost");
        return new ShippingCostSolutionScorer(divisor, config.dimensionalWeightLengthUnit(),
                config.dimensionalWeightWeightUnit());
    }

    static long requireDivisor(PackingConfig config, String objective) {
        return config.dimensionalWeightDivisor().orElseThrow(() -> new UnknownObjectiveException(
                "the " + objective + " objective requires configuration.dimensional_weight_divisor"));
    }

    /** The weight a carrier bills this container at, in weight ticks. */
    protected long billedTicks(PackedContainer packed) {
        return billedTicks(Optional.of(packed.billedDimensions()), packed.grossWeight().ticks(), divisor, lengthUnit,
                weightUnit);
    }

    /**
     * max(gross weight, dimensional weight of what the carrier measures): the declared outer box,
     * or a flat bag's achieved package; an empty bag bills nothing by volume. The one
     * definition every caller that prices a container uses, so the search, the score and the
     * final guard cannot bill the same container differently.
     */
    public static long billedTicks(Optional<Dimensions> shipped, long grossTicks, long divisor, String lengthUnit,
            String weightUnit) {
        long dimensional = shipped.map(box -> box.dimensionalWeight(divisor, lengthUnit, weightUnit).ticks()).orElse(0L);
        return Math.max(grossTicks, dimensional);
    }

    /** A container as a search state holds it, with the config's divisor and units. */
    public static long billedTicks(Container container, Optional<Dimensions> shipped, long payloadTicks,
            PackingConfig config) {
        return billedTicks(shipped, Math.addExact(container.tareWeight().ticks(), payloadTicks),
                requireDivisor(config, config.objective()), config.dimensionalWeightLengthUnit(),
                config.dimensionalWeightWeightUnit());
    }

    @Override
    public ObjectiveScore score(RawSolution solution) {
        ObjectiveScore base = new DefaultSolutionScorer().score(solution);
        long billable = 0;
        for (PackedContainer packed : solution.containers()) {
            billable = Math.addExact(billable, billedTicks(packed));
        }
        return ObjectiveScore.of(base.component(0), billable, base.component(1), base.component(3),
                base.component(4));
    }
}
