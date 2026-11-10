package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import java.util.Optional;

/**
 * The only facts load propagation needs about a box, real or hypothetical.
 *
 * <p>The compression fields are set only for a {@code compressible} item. Load
 * propagation already computes the cumulative mass above every unit, which is exactly the
 * numerator the pressure model needs, so the crush check rides the graph built anyway.
 */
public record LoadUnit(
        AxisAlignedBox box,
        long weightTicks,
        Optional<Long> maxTopLoadTicks,
        Optional<Long> maxStackedItems,
        String label,
        Optional<String> nestingItemId,
        Optional<Long> nestingHeightTicks,
        Optional<Long> compressionRatioPpm,
        Optional<Long> maxCompressionPressureKpa) {

    public LoadUnit(AxisAlignedBox box, long weightTicks, Optional<Long> maxTopLoadTicks,
            Optional<Long> maxStackedItems, String label, Optional<String> nestingItemId,
            Optional<Long> nestingHeightTicks) {
        this(box, weightTicks, maxTopLoadTicks, maxStackedItems, label, nestingItemId, nestingHeightTicks,
                Optional.empty(), Optional.empty());
    }

    public LoadUnit(AxisAlignedBox box, long weightTicks, Optional<Long> maxTopLoadTicks,
            Optional<Long> maxStackedItems, String label) {
        this(box, weightTicks, maxTopLoadTicks, maxStackedItems, label, Optional.empty(), Optional.empty());
    }
}
