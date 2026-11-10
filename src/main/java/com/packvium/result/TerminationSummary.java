package com.packvium.result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TerminationSummary {
    private TerminationSummary() {
    }

    /** Folds every start's ending into one {@code termination} fact. O(starts). */
    public static ResultFact aggregate(List<StartRecord> starts, boolean error) {
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("termination aggregation requires at least one start record");
        }
        List<StartRecord> selected = starts.stream().filter(StartRecord::selected).toList();
        if (selected.size() != 1) {
            throw new IllegalArgumentException("termination aggregation requires exactly one selected start");
        }
        boolean anyTruncated = starts.stream().anyMatch(StartRecord::truncated);
        boolean allCompleted = starts.stream().allMatch(StartRecord::completed);
        boolean globalDeadline = starts.stream().anyMatch(StartRecord::globalDeadlineReached);
        boolean winningTruncated = selected.get(0).truncated();
        String code = error ? "error" : (winningTruncated || globalDeadline) ? "time_limit" : "complete";
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("any_start_truncated", anyTruncated);
        attributes.put("all_required_starts_completed", allCompleted);
        attributes.put("winning_start_truncated", winningTruncated);
        attributes.put("global_deadline_reached", globalDeadline);
        attributes.put("starts", starts.stream().map(StartRecord::toMap).toList());
        return new ResultFact(code, attributes);
    }
}
