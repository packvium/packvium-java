package com.packvium.algorithm;

import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.ItemInstance;

public final class SupportCalculator {
    private SupportCalculator() {
    }

    /** Reported support fraction, from resting contact with other placements only. */
    public static double ratio(ContainerState state, ItemInstance item, Candidate candidate) {
        if (candidate.point().z() == 0) {
            return 1.0;
        }
        AxisAlignedBox box = new AxisAlignedBox(candidate.point(), candidate.envelopeDimensions());
        var support = LoadSupportGraph.candidateView(state.placements, item, box);
        return (double) support.supportingArea() / (double) candidate.envelopeDimensions().baseAreaTicks();
    }
}
