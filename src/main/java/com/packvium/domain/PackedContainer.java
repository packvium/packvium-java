package com.packvium.domain;

import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One opened container and what went into it.
 *
 * <p>{@code latticeItems} are the specific instances the compact fast path
 * consumed, in placement order -- needed to reconstruct identical Placement objects via
 * {@link #expandPlacements}. References to existing instances, so keeping them is far cheaper
 * than the Placement construction they replace.
 */
public record PackedContainer(
        Container container,
        long sequence,
        List<Placement> placements,
        Optional<LatticeSummary> latticeSummary,
        List<ItemInstance> latticeItems) {

    public PackedContainer {
        placements = List.copyOf(placements);
        latticeItems = List.copyOf(latticeItems);
    }

    public PackedContainer(Container container, long sequence, List<Placement> placements) {
        this(container, sequence, placements, Optional.empty(), List.of());
    }

    public String id() {
        return container.id() + "#" + sequence;
    }

    public long placementCount() {
        return latticeSummary.map(LatticeSummary::count).orElse((long) placements.size());
    }

    public Weight payloadWeight() {
        if (latticeSummary.isPresent()) {
            return new Weight(latticeSummary.get().totalWeightTicks());
        }
        long total = 0;
        for (Placement placement : placements) {
            total = Math.addExact(total, placement.instance().weight().ticks());
        }
        return new Weight(total);
    }

    public Weight grossWeight() {
        return new Weight(Math.addExact(container.tareWeight().ticks(), payloadWeight().ticks()));
    }

    public BigInteger usedVolume() {
        return latticeSummary.map(LatticeSummary::usedVolume).orElseGet(() -> Nesting.usedVolume(placements));
    }

    /** Both terms are rounded to doubles first, in the order every Packvium engine uses. */
    public double utilization() {
        Dimensions inner = container.innerDimensions();
        double volumeScore = (double) inner.length().ticks() * (double) inner.width().ticks()
                * (double) inner.height().ticks();
        return usedVolume().doubleValue() / volumeScore;
    }

    public long maxZTicks() {
        if (latticeSummary.isPresent()) {
            return latticeSummary.get().maxZTicks();
        }
        long top = 0;
        for (Placement placement : placements) {
            top = Math.max(top, placement.envelopeBox().z2());
        }
        return top;
    }

    public long centreOfMassOffsetPpm() {
        Dimensions inner = container.innerDimensions();
        if (latticeSummary.isPresent()) {
            return latticeSummary.get().centreOfMassOffsetPpm(inner.length().ticks(), inner.width().ticks());
        }
        return CentreOfMass.offsetPpm(inner, placements);
    }

    /**
     * The full per-item Placement list, reconstructed exactly from the lattice summary if the
     * compact fast path built this container, otherwise {@code placements} unchanged.
     */
    public List<Placement> expandPlacements() {
        return latticeSummary.map(summary -> summary.expand(latticeItems)).orElse(placements);
    }

    /**
     * The largest envelope x2, y2 and z2 of its placements: a flat bag's conformed package
     *, or a made-to-measure box's extent before it is rounded onto the machine grid
     *; empty for any other container. O(p).
     */
    public Optional<Dimensions> achievedDimensions() {
        if (!container.isFlatBag() && container.madeToMeasure().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FlatBag.extentOf(placements).toDimensions());
    }

    /** What a carrier measures: a bag's achieved package, else the declared outer size. */
    public Dimensions billedDimensions() {
        if (container.isFlatBag()) {
            return achievedDimensions().orElseThrow();
        }
        return container.outerDimensions().orElse(container.innerDimensions());
    }

    public Item asItem() {
        return asItem(Optional.empty(), true, Optional.empty());
    }

    public Item asItem(Optional<String> id, boolean keepUpright, Optional<Weight> maxTopLoad) {
        return Item.builder(id.orElseGet(this::id), billedDimensions())
                .weight(grossWeight())
                .quantity(1)
                .allowedRotations(Rotation.all())
                .keepUpright(keepUpright)
                .stackable(true)
                .mustBeOnFloor(false)
                .maxTopLoad(maxTopLoad.orElse(null))
                .metadata(Map.of("source_packed_container", id()))
                .build();
    }
}
