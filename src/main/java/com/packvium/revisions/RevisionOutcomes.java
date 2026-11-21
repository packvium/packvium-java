package com.packvium.revisions;

import com.packvium.outcomes.OutcomeEvent;
import com.packvium.outcomes.OutcomeEventType;
import com.packvium.outcomes.OutcomeLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Approved plan revisions as labeled evidence for historical evaluation.
 *
 * <p>Held to {@code packvium.revision_outcomes}. A revision records what differed from an approved
 * plan; this appends those records to an {@link OutcomeLedger}, where {@code com.packvium.holdout}
 * already evaluates recommendations on untouched history. It adds no second evaluator and no
 * second store. Three properties carry the acceptance, each structural rather than a check:
 *
 * <ul>
 *   <li><b>Traceable.</b> Every ledger event's id is the decision, the SHA-256 of the revision
 *       that recorded it and the event's sequence, so evidence behind a recommendation names one
 *       hashed, versioned document. The decision is part of the id because a ledger's ids are
 *       global.
 *   <li><b>Append-only.</b> Recording only calls {@link OutcomeLedger#record}, which never replaces
 *       an event. Neither the approved plan nor the revision is read back or changed, so an outcome
 *       cannot overwrite what was planned. Re-recording a revision is idempotent.
 *   <li><b>Learning cannot reach a result.</b> No solver, validator or request reads the ledger.
 * </ul>
 *
 * <p>The one input a revision does not carry is when it happened: revisions take no clock, so the
 * integrator passes {@code recordedAt} from its own system, as for any ledger event.
 */
public final class RevisionOutcomes {
    private RevisionOutcomes() {
    }

    /** The ledger events one revision's exceptions become. The root records none. O(E + D). */
    public static List<OutcomeEvent> events(Object revision, String decisionId, long recordedAt) {
        if (!(revision instanceof Map<?, ?> document) || !PlanRevision.FORMAT.equals(document.get("format"))) {
            throw new PlanRevisionException("invalid_revision", "not a " + PlanRevision.FORMAT + " document");
        }
        String digest = PlanRevision.digest(document);
        List<OutcomeEvent> events = new ArrayList<>();
        for (Object entry : (List<?>) document.get("events")) {
            Map<?, ?> event = (Map<?, ?>) entry;
            String type = (String) event.get("type");
            events.add(new OutcomeEvent(decisionId + "@" + digest + "#" + event.get("sequence"), decisionId,
                    eventType(type), payload(type, event), recordedAt));
        }
        return List.copyOf(events);
    }

    /** Append one revision's exceptions to {@code ledger}, and return what the ledger holds for them. */
    public static List<OutcomeEvent> record(OutcomeLedger ledger, Object revision, String decisionId,
            long recordedAt) {
        return events(revision, decisionId, recordedAt).stream().map(ledger::record).toList();
    }

    private static OutcomeEventType eventType(String type) {
        return switch (type) {
            case "item_missing" -> OutcomeEventType.ITEM_MISSING;
            case "container_substituted" -> OutcomeEventType.ACTUAL_CARTON;
            // A lock is an operator deciding where an item goes instead of the plan, which is what
            // an override is -- and why holdout reports it against the baseline, never the treatment.
            case "placement_locked" -> OutcomeEventType.OPERATOR_OVERRIDE;
            case "placement_verified" -> OutcomeEventType.PLACEMENT_VERIFIED;
            default -> throw new PlanRevisionException("invalid_event", "unknown event type " + type);
        };
    }

    private static Map<String, Object> payload(String type, Map<?, ?> event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (type) {
            case "item_missing" -> {
                payload.put("item_type", event.get("item_type"));
                payload.put("quantity", event.get("quantity"));
            }
            case "container_substituted" -> payload.put("carton_id", ((Map<?, ?>) event.get("replacement")).get("id"));
            case "placement_locked" -> payload.put("reason_code", "placement_locked");
            default -> {
                Map<?, ?> placement = (Map<?, ?>) event.get("placement");
                payload.put("item_type", placement.get("item_type"));
                payload.put("container_type", placement.get("container_type"));
            }
        }
        return payload;
    }
}
