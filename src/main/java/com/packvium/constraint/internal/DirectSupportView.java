package com.packvium.constraint.internal;

import com.packvium.domain.AxisAlignedBox;
import java.util.List;

/** Positive-area direct supporters of one candidate, in placement-index order. */
public record DirectSupportView(
        List<Integer> supporterIndexes,
        List<AxisAlignedBox> supporterBoxes,
        List<Long> supporterAreas,
        long supportingArea) {

    public DirectSupportView {
        supporterIndexes = List.copyOf(supporterIndexes);
        supporterBoxes = List.copyOf(supporterBoxes);
        supporterAreas = List.copyOf(supporterAreas);
    }
}
