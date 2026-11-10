package com.packvium.algorithm;

import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.support.SortKey;

/** One feasible placement: envelope corner, physical position, orientation and its score. */
public record Candidate(
        Point point,
        Point position,
        Rotation rotation,
        Dimensions dimensions,
        Dimensions envelopeDimensions,
        SortKey score) {
}
