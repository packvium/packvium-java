package com.packvium.outcomes;

import com.packvium.support.PythonRepr;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable fact about one decision: which alternative was chosen, the carton really used, a
 * measurement, a repack, damage, a return or an operator override.
 *
 * <p>Never changed once recorded. A correction is a <em>new</em> event whose {@code supersedes}
 * names this one's id, so the history of what was believed is kept.
 *
 * @param eventId this event's own id, which makes a duplicate delivery idempotent
 * @param decisionId the decision it is about, typically the job's idempotency key in the caller's system
 * @param eventType what kind of fact it records
 * @param payload the fact itself, restricted to {@link OutcomeEventType#allowedFields()}
 * @param recordedAt when it was recorded, in the caller's epoch milliseconds
 * @param supersedes the id of an earlier event this one corrects
 */
public record OutcomeEvent(String eventId, String decisionId, OutcomeEventType eventType,
        Map<String, Object> payload, long recordedAt, Optional<String> supersedes) {
    /** Refuses missing ids, a negative time, a self-correction and any field outside the vocabulary. */
    public OutcomeEvent {
        Objects.requireNonNull(eventType, "eventType");
        if (eventId == null || eventId.isEmpty()) {
            throw new IllegalArgumentException("event_id is required");
        }
        if (decisionId == null || decisionId.isEmpty()) {
            throw new IllegalArgumentException("decision_id is required");
        }
        if (recordedAt < 0) {
            throw new IllegalArgumentException("recorded_at cannot be negative");
        }
        if (supersedes.isPresent() && supersedes.get().equals(eventId)) {
            throw new IllegalArgumentException("an event cannot supersede itself");
        }
        validatePayload(eventType, payload);
        // A null-tolerant copy: a payload value may be absent, as the reference's None may be.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** An original event, correcting nothing. */
    public OutcomeEvent(String eventId, String decisionId, OutcomeEventType eventType, Map<String, Object> payload,
            long recordedAt) {
        this(eventId, decisionId, eventType, payload, recordedAt, Optional.empty());
    }

    private static void validatePayload(OutcomeEventType eventType, Map<String, Object> payload) {
        List<String> unsupported = new ArrayList<>();
        for (String field : payload.keySet()) {
            if (!eventType.allowedFields().contains(field)) {
                unsupported.add(field);
            }
        }
        if (unsupported.isEmpty()) {
            return;
        }
        unsupported.sort(SortKey::compareCodePoints);
        List<String> allowed = new ArrayList<>(eventType.allowedFields());
        allowed.sort(SortKey::compareCodePoints);
        throw new UnsupportedOutcomeFieldError("event type " + PythonRepr.string(eventType.value())
                + " does not support field(s) " + PythonRepr.strings(unsupported)
                + "; allowed: " + PythonRepr.strings(allowed));
    }
}
