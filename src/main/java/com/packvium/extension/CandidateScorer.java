package com.packvium.extension;

import com.packvium.algorithm.ContainerState;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import com.packvium.support.SortKey;

/**
 * Ranks feasible placements; lower sorts first. Takes the origin and envelope rather than a
 * Candidate so the finder need not build a throwaway object per point and rotation.
 */
public interface CandidateScorer {
    SortKey score(ContainerState state, Point point, Dimensions envelope);
}
