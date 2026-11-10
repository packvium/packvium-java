package com.packvium.constraint.internal;

import com.packvium.domain.AxisAlignedBox;
import java.util.List;

/** Candidate support surfaces plus canonical positive-area direct supporters. */
public record DirectSupportView(
        List<AxisAlignedBox> surfaces,
        List<Integer> supporterIndexes,
        List<AxisAlignedBox> supporterBoxes,
        List<Long> supporterAreas,
        long supportingArea) {

    public DirectSupportView {
        surfaces = List.copyOf(surfaces);
        supporterIndexes = List.copyOf(supporterIndexes);
        supporterBoxes = List.copyOf(supporterBoxes);
        supporterAreas = List.copyOf(supporterAreas);
    }
}
