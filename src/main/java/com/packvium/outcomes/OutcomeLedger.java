package com.packvium.outcomes;

import com.packvium.support.PythonRepr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Append-only store of {@link OutcomeEvent}s, indexed by decision id.
 *
 * <p>Closing the operating loop needs a ledger rather than a mutable "decision" row a later event
 * could quietly edit. A duplicate delivery of the same event is idempotent, a correction is a new
 * event that names the one it supersedes, and {@link #eventsForDecision} always returns everything
 * ever recorded while {@link #currentView} and {@link #viewAsOf} fold corrections in.
 *
 * <p>The ledger never reads, re-runs or overrides a packing decision's validation: it only appends
 * structured facts <em>about</em> a decision some already validated system produced. This is an
 * in-memory reference implementation of the contract, like the commerce registries; a production
 * deployment backs it with durable, truly append-only storage. Not thread-safe.
 */
public final class OutcomeLedger {
    // Looked up by id only; recorded order lives in byDecision, so no hash order reaches output.
    private final Map<String, OutcomeEvent> byId = new HashMap<>();
    private final Map<String, List<String>> byDecision = new HashMap<>();

    /**
     * Append {@code event}, or return the one already recorded under its id when the content is
     * identical (an idempotent duplicate delivery). O(1) expected.
     *
     * @throws DuplicateEventMismatchError if the id was recorded with different content
     * @throws OutcomeEventNotFoundError if the event supersedes an id the ledger has never seen
     */
    public OutcomeEvent record(OutcomeEvent event) {
        OutcomeEvent existing = byId.get(event.eventId());
        if (existing != null) {
            if (!existing.equals(event)) {
                throw new DuplicateEventMismatchError("event id " + PythonRepr.string(event.eventId())
                        + " was already recorded with different content");
            }
            return existing;
        }
        Optional<String> supersedes = event.supersedes();
        if (supersedes.isPresent() && !byId.containsKey(supersedes.get())) {
            throw new OutcomeEventNotFoundError("event " + PythonRepr.string(event.eventId())
                    + " supersedes unknown event " + PythonRepr.string(supersedes.get()));
        }
        byId.put(event.eventId(), event);
        byDecision.computeIfAbsent(event.decisionId(), unused -> new ArrayList<>()).add(event.eventId());
        return event;
    }

    /**
     * The complete, unfolded history of one decision in recorded order, including every event a
     * later correction superseded. Empty for a decision never recorded. O(e) for its e events.
     */
    public List<OutcomeEvent> eventsForDecision(String decisionId) {
        List<OutcomeEvent> events = new ArrayList<>();
        for (String eventId : byDecision.getOrDefault(decisionId, List.of())) {
            events.add(byId.get(eventId));
        }
        return List.copyOf(events);
    }

    /**
     * What was believed about one decision at time {@code at}: events recorded strictly before
     * it, folded with only the corrections also recorded by then.
     *
     * <p>{@link #currentView} cannot answer this. It folds every correction ever recorded, so
     * using it to reconstruct a past belief pulls later knowledge backward through time, and a
     * holdout evaluation built on it would report a backtest nobody could have run.
     * O(e).
     *
     * @throws IllegalArgumentException if {@code at} is negative
     */
    public List<OutcomeEvent> viewAsOf(String decisionId, long at) {
        if (at < 0) {
            throw new IllegalArgumentException("as-of time cannot be negative");
        }
        List<OutcomeEvent> known = eventsForDecision(decisionId).stream()
                .filter(event -> event.recordedAt() < at)
                .toList();
        // A correction recorded later leaves the event it supersedes standing, because that is
        // what was believed at the time.
        return withoutSuperseded(known);
    }

    /**
     * The current, corrected understanding of one decision: every recorded event except those a
     * correction supersedes. History is not discarded; this only filters. O(e).
     */
    public List<OutcomeEvent> currentView(String decisionId) {
        return withoutSuperseded(eventsForDecision(decisionId));
    }

    private static List<OutcomeEvent> withoutSuperseded(List<OutcomeEvent> events) {
        Set<String> superseded = new HashSet<>();
        for (OutcomeEvent event : events) {
            event.supersedes().ifPresent(superseded::add);
        }
        return events.stream().filter(event -> !superseded.contains(event.eventId())).toList();
    }
}
