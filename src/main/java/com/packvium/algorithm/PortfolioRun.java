package com.packvium.algorithm;

import com.packvium.result.StartRecord;
import java.util.List;

public record PortfolioRun(List<RawSolution> solutions, List<StartRecord> starts) {
    public PortfolioRun {
        solutions = List.copyOf(solutions);
        starts = List.copyOf(starts);
    }
}
