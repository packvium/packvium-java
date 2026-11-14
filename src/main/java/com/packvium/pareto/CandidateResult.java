package com.packvium.pareto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One engine's result on one profile, already validated by the caller.
 *
 * <p>A candidate the caller's validator disqualified must be left out before it reaches
 * {@link Pareto#generateReport}: the report assumes every candidate it receives is a valid
 * packing. The metrics keep the caller's insertion order, which only decides which axis a
 * {@code NaN} refusal names first.
 *
 * @param profile the profile the result belongs to, such as {@code balanced}
 * @param engine the name the report lists the result under
 * @param metrics the result's value on each axis; direction is given separately
 */
public record CandidateResult(String profile, String engine, Map<String, Double> metrics) {
    /** Refuses an unnamed profile or engine, empty metrics, and any {@code NaN}. */
    public CandidateResult {
        if (profile == null || profile.isEmpty()) {
            throw new IllegalArgumentException("profile is required");
        }
        if (engine == null || engine.isEmpty()) {
            throw new IllegalArgumentException("engine is required");
        }
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("metrics cannot be empty");
        }
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        // At construction rather than at comparison: an invalid candidate then cannot exist, so
        // the report inherits the guarantee without paying for it once per pair.
        Pareto.refuseNan(metrics, engine + " on profile " + profile);
    }
}
