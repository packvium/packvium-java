package com.packvium.result;

import java.util.LinkedHashMap;
import java.util.Map;

public record SolverMetrics(
        long candidatePointsConsidered,
        long orientationsConsidered,
        long feasibleCandidates,
        long collisionChecks,
        long supportChecks,
        long spacePartitions,
        long searchNodesExpanded) {

    public static final SolverMetrics NONE = new SolverMetrics(0, 0, 0, 0, 0, 0, 0);

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("candidate_points_considered", candidatePointsConsidered);
        rendered.put("orientations_considered", orientationsConsidered);
        rendered.put("feasible_candidates", feasibleCandidates);
        rendered.put("collision_checks", collisionChecks);
        rendered.put("support_checks", supportChecks);
        rendered.put("space_partitions", spacePartitions);
        rendered.put("search_nodes_expanded", searchNodesExpanded);
        return rendered;
    }
}
