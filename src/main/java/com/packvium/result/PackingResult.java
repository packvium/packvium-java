package com.packvium.result;

import com.packvium.domain.PackedContainer;
import com.packvium.domain.UnpackedItem;
import java.util.List;

/** One ranked answer: its containers, what it left behind, and the facts about how it was found. */
public record PackingResult(
        PackingStatus status,
        List<PackedContainer> containers,
        List<UnpackedItem> unpacked,
        AlgorithmReport algorithm,
        List<Long> score,
        List<String> warnings,
        List<PackingResult> alternatives,
        ResultFact feasibility,
        ResultFact termination,
        ResultFact optimality,
        String objective) {

    public PackingResult {
        containers = List.copyOf(containers);
        unpacked = List.copyOf(unpacked);
        score = List.copyOf(score);
        warnings = List.copyOf(warnings);
        alternatives = List.copyOf(alternatives);
    }

    /** Derives feasibility and optimality from the status, as the reference engines do. */
    public static PackingResult of(PackingStatus status, List<PackedContainer> containers, List<UnpackedItem> unpacked,
            AlgorithmReport algorithm, List<Long> score, List<String> warnings, ResultFact termination,
            String objective) {
        boolean complete = unpacked.isEmpty();
        return new PackingResult(status, containers, unpacked, algorithm, score, warnings, List.of(),
                new ResultFact(feasibility(status, complete)), termination,
                new ResultFact(optimality(status, complete)), objective);
    }

    public PackingResult withAlternatives(List<PackingResult> chosen) {
        return new PackingResult(status, containers, unpacked, algorithm, score, warnings, chosen, feasibility,
                termination, optimality, objective);
    }

    public boolean complete() {
        return unpacked.isEmpty();
    }

    public long packedItemCount() {
        return containers.stream().mapToLong(PackedContainer::placementCount).sum();
    }

    private static String feasibility(PackingStatus status, boolean complete) {
        if (status == PackingStatus.INFEASIBLE) {
            return "infeasible";
        }
        return complete && status != PackingStatus.INVALID_RESULT ? "feasible" : "unknown";
    }

    private static String optimality(PackingStatus status, boolean complete) {
        if (status == PackingStatus.OPTIMAL) {
            return "proven_optimal";
        }
        if (status == PackingStatus.INFEASIBLE) {
            return "proven_infeasible";
        }
        return !complete && status != PackingStatus.INVALID_RESULT ? "best_found" : "not_proven";
    }
}
