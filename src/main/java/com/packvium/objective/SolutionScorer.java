package com.packvium.objective;

import com.packvium.algorithm.RawSolution;

public interface SolutionScorer {
    ObjectiveScore score(RawSolution solution);
}
