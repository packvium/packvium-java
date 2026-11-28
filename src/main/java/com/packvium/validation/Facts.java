package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The result's own claims about the search: counters, counted-work limits, start records, facts. */
final class Facts {
    private static final List<String> METRICS = List.of("candidate_points_considered", "orientations_considered",
            "feasible_candidates", "collision_checks", "support_checks", "space_partitions", "search_nodes_expanded");
    private static final List<String> FLAGS = List.of("started", "completed", "truncated", "selected",
            "global_deadline_reached");

    private Facts() {
    }

    private static Object algorithm(Object result) {
        Object algorithm = Json.at(result, "algorithm");
        if (!Json.isDict(algorithm)) {
            throw MalformedResult.contract("algorithm must be an object");
        }
        return algorithm;
    }

    static void checkAlgorithmMetrics(Object result, List<ResultIssue> issues) {
        Object algorithm = algorithm(result);
        Object metrics = Json.get(algorithm, "metrics");
        boolean wellFormed = Json.isDict(metrics);
        for (String field : METRICS) {
            BigInteger count = wellFormed ? Json.strictInt(Json.get(metrics, field)) : null;
            wellFormed = wellFormed && count != null && count.signum() >= 0;
        }
        if (!wellFormed) {
            issues.add(new ResultIssue("solver_metrics_mismatch",
                    "algorithm.metrics must contain every non-negative integer counter"));
            return;
        }
        BigInteger candidates = Json.strictInt(Json.get(algorithm, "candidates_evaluated"));
        BigInteger attempts = Json.strictInt(Json.get(algorithm, "placements_attempted"));
        if (candidates == null || attempts == null
                || !candidates.equals(Json.strictInt(Json.get(metrics, "feasible_candidates")))
                || !attempts.equals(Json.strictInt(Json.get(metrics, "orientations_considered")))) {
            issues.add(new ResultIssue("solver_metrics_mismatch", "legacy candidate counters must mirror algorithm.metrics"));
        }
    }

    /** Counted-work limits are respected, and the stop flag is evidenced by one. */
    static void checkEffortBudget(Object request, Object result, List<ResultIssue> issues) {
        Object algorithm = Json.at(result, "algorithm");
        Object effortLimited = Json.get(algorithm, "effort_limit_reached");
        if (!(effortLimited instanceof Boolean limited)) {
            throw MalformedResult.contract("algorithm.effort_limit_reached must be a boolean");
        }
        Object budget = Json.get(Json.get(request, "configuration", Map.of()), "effort_budget");
        if (budget == null) {
            if (limited) {
                issues.add(new ResultIssue("effort_budget_mismatch",
                        "effort_limit_reached is true but the request has no effort_budget"));
            }
            return;
        }
        if (!Json.isDict(budget)) {
            throw MalformedResult.contract("config.effort_budget must be an object");
        }
        Object metrics = Json.get(algorithm, "metrics");
        if (!Json.isDict(metrics)) {
            return;
        }
        boolean reached = false;
        Map<String, String> limits = new LinkedHashMap<>();
        limits.put("max_candidates_evaluated", "feasible_candidates");
        limits.put("max_placement_attempts", "orientations_considered");
        limits.put("max_search_nodes", "search_nodes_expanded");
        for (Map.Entry<String, String> pair : limits.entrySet()) {
            Object limit = Json.get(budget, pair.getKey());
            BigInteger value = Json.strictInt(Json.get(metrics, pair.getValue()));
            if (limit == null || value == null) {
                continue;
            }
            int order = Json.compare(value, limit);
            if (order > 0) {
                issues.add(new ResultIssue("effort_budget_exceeded", pair.getValue() + " is " + value + ", above "
                        + pair.getKey() + "=" + PyFormat.str(limit)));
            }
            reached = reached || order >= 0;
        }
        Object restartLimit = Json.get(budget, "max_restarts");
        Object starts = Json.get(Json.get(result, "termination", Map.of()), "starts", List.of());
        BigInteger limit = Json.intInstance(restartLimit);
        if (limit != null && starts instanceof List<?> records) {
            long started = records.stream()
                    .filter(start -> Json.isDict(start) && Boolean.TRUE.equals(Json.get(start, "started"))).count();
            if (BigInteger.valueOf(started).compareTo(limit) > 0) {
                issues.add(new ResultIssue("effort_budget_exceeded", "started " + started
                        + " solver runs, above max_restarts=" + PyFormat.str(restartLimit)));
            }
            reached = reached || BigInteger.valueOf(started).compareTo(limit) >= 0;
        }
        if (limited && !reached) {
            issues.add(new ResultIssue("effort_budget_mismatch",
                    "effort_limit_reached is true but no configured counted-work boundary was reached"));
        }
    }

    private static boolean flag(Object algorithm, String name) {
        if (!(Json.get(algorithm, name) instanceof Boolean value)) {
            throw MalformedResult.contract("algorithm." + name + " must be a boolean");
        }
        return value;
    }

    private static String factCode(Object result, String name) {
        Object fact = Json.at(result, name);
        if (!Json.isDict(fact)) {
            throw MalformedResult.contract(name + " must be an object");
        }
        if (!(Json.get(fact, "code") instanceof String code) || code.isEmpty()) {
            throw MalformedResult.contract(name + ".code must be a non-empty string");
        }
        return code;
    }

    static void checkResultFacts(Object result, List<ResultIssue> issues) {
        Object algorithm = algorithm(result);
        boolean timedOut = flag(algorithm, "time_limit_reached");
        boolean effortLimited = flag(algorithm, "effort_limit_reached");
        if (timedOut && effortLimited) {
            issues.add(new ResultIssue("result_fact_mismatch",
                    "time_limit_reached and effort_limit_reached cannot both be true"));
        }
        Object termination = Json.at(result, "termination");
        if (!Json.isDict(termination)) {
            throw MalformedResult.contract("termination must be an object");
        }
        checkStarts(termination, algorithm, timedOut, effortLimited, issues);
        Object status = Json.at(result, "status");
        boolean complete = Json.truthy(Json.at(result, "complete"));
        boolean invalid = Json.isText(status, "invalid_result");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("feasibility", Json.isText(status, "infeasible") ? "infeasible"
                : complete && !invalid ? "feasible" : "unknown");
        expected.put("termination", invalid ? "error" : timedOut ? "time_limit" : effortLimited ? "effort_limit"
                : "complete");
        expected.put("optimality", Json.isText(status, "optimal") ? "proven_optimal"
                : Json.isText(status, "infeasible") ? "proven_infeasible"
                : !complete && !invalid ? "best_found" : "not_proven");
        for (Map.Entry<String, String> fact : expected.entrySet()) {
            String actual = factCode(result, fact.getKey());
            if (!actual.equals(fact.getValue())) {
                issues.add(new ResultIssue("result_fact_mismatch", fact.getKey() + ".code is " + PyFormat.string(actual)
                        + ", expected " + PyFormat.string(fact.getValue())));
            }
        }
    }

    private static boolean isTrue(Object start, String name) {
        return Json.isDict(start) && Boolean.TRUE.equals(Json.get(start, name));
    }

    private static void checkStarts(Object termination, Object algorithm, boolean timedOut, boolean effortLimited,
            List<ResultIssue> issues) {
        Object declared = Json.get(termination, "starts");
        if (!(declared instanceof List<?> starts) || starts.isEmpty()) {
            issues.add(new ResultIssue("termination_aggregate_mismatch", "termination.starts must contain at least one start"));
            return;
        }
        boolean malformed = false;
        for (Object start : starts) {
            if (!Json.isDict(start) || !(Json.get(start, "id") instanceof String)) {
                malformed = true;
                continue;
            }
            List<Boolean> flags = new ArrayList<>();
            for (String name : FLAGS) {
                if (Json.get(start, name) instanceof Boolean value) {
                    flags.add(value);
                }
            }
            if (flags.size() != FLAGS.size()) {
                malformed = true;
                continue;
            }
            boolean started = flags.get(0);
            boolean completed = flags.get(1);
            boolean truncated = flags.get(2);
            if ((completed && (!started || truncated)) || (truncated && (!started || completed))
                    || (!started && (completed || truncated))) {
                issues.add(new ResultIssue("start_record_mismatch", Json.at(start, "id") + ": inconsistent lifecycle flags"));
            }
        }
        if (malformed) {
            issues.add(new ResultIssue("start_record_mismatch",
                    "a start record is missing its id or boolean lifecycle fields"));
        }
        List<Object> selected = new ArrayList<>();
        for (Object start : starts) {
            if (isTrue(start, "selected")) {
                selected.add(start);
            }
        }
        if (selected.size() != 1) {
            issues.add(new ResultIssue("termination_aggregate_mismatch", "expected one selected start, found "
                    + selected.size()));
            return;
        }
        if (!starts.stream().allMatch(Json::isDict)) {
            return;
        }
        Object winner = selected.get(0);
        Map<String, Boolean> aggregate = new LinkedHashMap<>();
        aggregate.put("any_start_truncated", starts.stream().anyMatch(start -> isTrue(start, "truncated")));
        aggregate.put("all_required_starts_completed", starts.stream().allMatch(start -> isTrue(start, "completed")));
        aggregate.put("winning_start_truncated", isTrue(winner, "truncated"));
        aggregate.put("global_deadline_reached", starts.stream().anyMatch(start -> isTrue(start, "global_deadline_reached")));
        for (Map.Entry<String, Boolean> entry : aggregate.entrySet()) {
            Object reported = Json.get(termination, entry.getKey());
            if (!(reported instanceof Boolean flag) || flag != entry.getValue()) {
                issues.add(new ResultIssue("termination_aggregate_mismatch", entry.getKey() + " is "
                        + PyFormat.repr(reported) + ", expected " + PyFormat.boolRepr(entry.getValue())));
            }
        }
        Object solver = Json.get(algorithm, "solver");
        Object selectedId = Json.get(winner, "id", "");
        if (solver instanceof String name && !Json.isText(selectedId, name)
                && !Json.text(selectedId, "a start id").startsWith(name + "#")) {
            issues.add(new ResultIssue("winning_start_mismatch", "selected start " + PyFormat.repr(selectedId)
                    + " does not identify solver " + PyFormat.string(name)));
        }
        if (aggregate.get("global_deadline_reached") && !timedOut) {
            issues.add(new ResultIssue("legacy_time_limit_mismatch", "global_deadline_reached requires time_limit_reached"));
        }
        boolean limited = aggregate.get("global_deadline_reached") || aggregate.get("winning_start_truncated");
        if (limited != (timedOut || effortLimited)) {
            issues.add(new ResultIssue("termination_limit_mismatch",
                    "selected-start truncation must identify exactly one reached limit"));
        }
    }
}
