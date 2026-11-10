package com.packvium.extension;

import com.packvium.algorithm.ContainerState;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import com.packvium.support.SortKey;

/** Prefers low, tight placements. Every component is an exact integer. */
public final class DefaultCandidateScorer implements CandidateScorer {
    @Override
    public SortKey score(ContainerState state, Point point, Dimensions envelope) {
        Dimensions inner = state.container.innerDimensions();
        long newHeight = Math.max(state.maxZ, point.z() + envelope.height().ticks());
        long rx = inner.length().ticks() - (point.x() + envelope.length().ticks());
        long ry = inner.width().ticks() - (point.y() + envelope.width().ticks());
        long rz = inner.height().ticks() - (point.z() + envelope.height().ticks());
        return SortKey.of(point.z(), newHeight, Math.addExact(Math.multiplyExact(rx, ry), rz), point.y(), point.x());
    }
}
