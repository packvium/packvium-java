package com.packvium.revisions;

import com.packvium.artifacts.OperationalArtifact;
import com.packvium.support.CanonicalJson;
import com.packvium.support.CanonicalJsonException;
import com.packvium.support.FixedPlacementShape;
import com.packvium.support.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Plan revisions: an append-only chain of exceptions against approved plans.
 *
 * <p>Every Packvium engine derives the same revisions.
 * A revision records what differed from an approved plan -- a missing item, a substituted carton,
 * a lock, a verified placement -- and derives the request the next plan solves. It is a pure
 * function of its parent, the approved artifact and its events: it calls no solver, validator or
 * clock, so four builders emit the same bytes. A replan is then an ordinary solve of the derived
 * request: locks and verified placements become {@code fixed_placements}.
 *
 * <p>Documents are the {@code Map}/{@code List} trees {@link com.packvium.serialization.JsonReader}
 * yields; every document returned is a deep, unmodifiable copy, and no input is ever changed.
 */
public final class PlanRevision {
    public static final String FORMAT = "packvium-plan-revision/v1";
    public static final List<String> EVENT_TYPES = List.of("item_missing", "container_substituted",
            "placement_locked", "placement_verified");

    private static final Map<String, List<String>> EVENT_FIELDS = Map.of(
            "item_missing", List.of("item_type", "quantity"),
            "container_substituted", List.of("container_type", "replacement"),
            "placement_locked", List.of("placement"),
            "placement_verified", List.of("placement"));
    private static final List<String> PLACEMENT_FIELDS = List.of("item_type", "container_type",
            "container_instance", "position", "orientation");

    private PlanRevision() {
    }

    /** Revision 0: the request as first approved, with nothing recorded against it. */
    public static Map<String, Object> root(Object request) {
        if (!(request instanceof Map<?, ?>)) {
            throw new PlanRevisionException("invalid_revision", "a request is a JSON object");
        }
        Map<String, Object> document = document(0, null, null, new ArrayList<>(), thaw(request));
        canonicalJson(document);
        return frozen(document);
    }

    /**
     * The next revision: {@code events}, observed against {@code approvedArtifact}, applied to
     * {@code parent}.
     *
     * <p>Copies and hashes the request and artifact in their serialized size. Placement-only
     * replay indexes one canonical key per fixed placement; missing-item and carton events still
     * scan their request lists, so the whole operation is not unconditionally O(R + E).
     */
    public static Map<String, Object> derive(Object parent, Object approvedArtifact, Object events) {
        Map<?, ?> revision = requireRevision(parent);
        Map<?, ?> artifact = requireArtifact(approvedArtifact, revision.get("request"));
        if (!(events instanceof List<?> list) || list.isEmpty()) {
            throw new PlanRevisionException("invalid_event", "a revision records at least one event");
        }
        long first = lastSequence(revision) + 1;
        List<Object> recorded = new ArrayList<>();
        for (int offset = 0; offset < list.size(); offset++) {
            recorded.add(event(list.get(offset), first + offset));
        }
        Map<String, Object> request = applyAll(revision.get("request"), recorded);
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("artifact", digest(artifact));
        approved.put("replay", thaw(((Map<?, ?>) artifact.get("provenance")).get("replay")));
        long number = JsonValue.integer(revision.get("revision")).orElseThrow() + 1;
        Map<String, Object> document = document(number, digest(revision), approved, recorded, request);
        canonicalJson(document);
        return frozen(document);
    }

    /**
     * The request these events derive from {@code request}. Refuses a contradiction, never
     * physics: whether the new fixed set can hold is the engine's admission check when it is
     * solved.
     */
    public static Map<String, Object> applyEvents(Object request, Object events) {
        return frozen(applyAll(request, events));
    }

    /**
     * Every way the chain fails to be what its root and events derive, without stopping at the
     * first. {@code artifacts.get(k)}, when given, is the artifact revision k names as approved;
     * {@code artifacts} may be null. A chain or an artifact list that is not a JSON array is
     * refused rather than audited: there is no position to anchor an issue on.
     */
    public static List<RevisionIssue> verifyChain(Object revisions, Object artifacts) {
        if (!(revisions instanceof List<?> chain)) {
            throw new PlanRevisionException("invalid_revision", "a chain is a JSON array");
        }
        if (artifacts != null && !(artifacts instanceof List)) {
            throw new PlanRevisionException("invalid_artifact", "artifacts is a JSON array");
        }
        List<?> approvedArtifacts = (List<?>) artifacts;
        List<RevisionIssue> issues = new ArrayList<>();
        long lastSequence = 0;
        for (int position = 0; position < chain.size(); position++) {
            Map<?, ?> revision;
            try {
                revision = requireRevision(chain.get(position));
            } catch (PlanRevisionException error) {
                issues.add(new RevisionIssue("invalid_revision", position, error.getMessage()));
                return List.copyOf(issues);
            }
            long number = JsonValue.integer(revision.get("revision")).orElseThrow();
            if (number != position) {
                issues.add(new RevisionIssue("revision_number", position,
                        "revision " + number + " at position " + position));
            }
            Map<?, ?> parent = position > 0 ? (Map<?, ?>) chain.get(position - 1) : null;
            String expectedParent = parent == null ? null : digest(parent);
            Object recordedParent = revision.get("parent");
            if (!Objects.equals(recordedParent, expectedParent)) {
                issues.add(new RevisionIssue("parent_mismatch", position,
                        "parent " + plain(recordedParent) + ", expected " + plain(expectedParent)));
            }
            lastSequence = checkSequences(revision, position, lastSequence, issues);
            if (parent != null) {
                checkRequest(revision, parent, position, issues);
            }
            Object artifact = approvedArtifacts == null || position >= approvedArtifacts.size()
                    ? null
                    : approvedArtifacts.get(position);
            if (artifact != null && parent != null) {
                checkArtifact(revision, parent, artifact, position, issues);
            }
        }
        return List.copyOf(issues);
    }

    /** {@code sha256:} and the hex SHA-256 of a document's canonical bytes. */
    public static String digest(Object document) {
        byte[] bytes = canonicalJson(document).getBytes(StandardCharsets.UTF_8);
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }

    /** The RFC 8785 canonical form, the bytes a digest and every engine are compared on. */
    public static String canonicalJson(Object document) {
        try {
            return CanonicalJson.encode(document);
        } catch (CanonicalJsonException error) {
            throw new PlanRevisionException(error.errorCode(), error.getMessage(), error);
        }
    }

    // ----------------------------------------------------------------------------- building

    private static Map<String, Object> document(long number, String parent, Map<String, Object> approved,
            List<Object> events, Map<String, Object> request) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("suite_version", OperationalArtifact.SUITE_VERSION);
        document.put("revision", number);
        document.put("parent", parent);
        document.put("approved", approved);
        document.put("events", events);
        document.put("request", request);
        return document;
    }

    private static Map<?, ?> requireRevision(Object document) {
        if (!(document instanceof Map<?, ?> revision) || !FORMAT.equals(revision.get("format"))) {
            throw new PlanRevisionException("invalid_revision", "not a " + FORMAT + " document");
        }
        Optional<Long> number = JsonValue.integer(revision.get("revision"));
        if (number.isEmpty() || number.get() < 0) {
            throw new PlanRevisionException("invalid_revision", "revision is a non-negative integer");
        }
        if (!(revision.get("request") instanceof Map)) {
            throw new PlanRevisionException("invalid_revision", "a revision carries its request");
        }
        if (!(revision.get("events") instanceof List<?> events)) {
            throw new PlanRevisionException("invalid_revision", "a revision carries its events");
        }
        for (Object event : events) {
            if (!(event instanceof Map<?, ?> object) || JsonValue.integer(object.get("sequence")).isEmpty()) {
                throw new PlanRevisionException("invalid_revision",
                        "a revision's events each carry an integer sequence");
            }
        }
        return revision;
    }

    private static Map<?, ?> requireArtifact(Object document, Object request) {
        if (!(document instanceof Map<?, ?> artifact) || !OperationalArtifact.FORMAT.equals(artifact.get("format"))) {
            throw new PlanRevisionException("invalid_artifact", "not a " + OperationalArtifact.FORMAT + " document");
        }
        if (!(artifact.get("provenance") instanceof Map<?, ?> provenance) || !(provenance.get("replay") instanceof Map)) {
            throw new PlanRevisionException("invalid_artifact", "the artifact carries no provenance.replay");
        }
        if (!same(provenance.get("request"), request)) {
            throw new PlanRevisionException("invalid_artifact",
                    "the artifact was built from a different request than the parent's");
        }
        return artifact;
    }

    private static long lastSequence(Map<?, ?> revision) {
        List<?> events = (List<?>) revision.get("events");
        if (!events.isEmpty()) {
            return sequenceOf(events.get(events.size() - 1));
        }
        if (JsonValue.integer(revision.get("revision")).orElseThrow() != 0) {
            throw new PlanRevisionException("invalid_revision", "only the root revision records no events");
        }
        return 0;
    }

    private static Object event(Object raw, long sequence) {
        Map<?, ?> event = requireShape(raw);
        Optional<Long> recorded = JsonValue.integer(event.get("sequence"));
        if (recorded.isEmpty() || recorded.get() != sequence) {
            throw new PlanRevisionException("invalid_event", "event sequence " + CanonicalJson.spelling(event.get("sequence"))
                    + " does not continue the chain at " + sequence);
        }
        return thaw(event);
    }

    private static Map<?, ?> requireShape(Object raw) {
        if (!(raw instanceof Map<?, ?> event)) {
            throw new PlanRevisionException("invalid_event", "an event is a JSON object");
        }
        Object kind = event.get("type");
        if (!(kind instanceof String type) || !EVENT_FIELDS.containsKey(type)) {
            throw new PlanRevisionException("invalid_event", "unknown event type " + CanonicalJson.spelling(kind));
        }
        List<String> fields = EVENT_FIELDS.get(type);
        List<String> allowed = new ArrayList<>(fields);
        allowed.add("sequence");
        allowed.add("type");
        List<String> unknown = JsonValue.namesOutside(event, allowed);
        if (!unknown.isEmpty()) {
            throw new PlanRevisionException("invalid_event", type + " does not carry " + CanonicalJson.spelling(unknown));
        }
        List<String> missing = fields.stream().filter(field -> !event.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new PlanRevisionException("invalid_event", type + " needs " + CanonicalJson.spelling(missing));
        }
        switch (type) {
            case "item_missing" -> validateItemMissing(event);
            case "container_substituted" -> validateContainerSubstituted(event);
            default -> validatePlacement(event);
        }
        return event;
    }

    private static void validateItemMissing(Map<?, ?> event) {
        requireName(event.get("item_type"), "item_type");
        Optional<Long> quantity = JsonValue.integer(event.get("quantity"));
        if (quantity.isEmpty() || quantity.get() < 1) {
            throw new PlanRevisionException("invalid_event", "item_missing.quantity is a positive integer");
        }
    }

    private static void validateContainerSubstituted(Map<?, ?> event) {
        requireName(event.get("container_type"), "container_type");
        if (!(event.get("replacement") instanceof Map<?, ?> replacement)) {
            throw new PlanRevisionException("invalid_event", "a replacement is a container object");
        }
        requireName(replacement.get("id"), "replacement.id");
    }

    private static void validatePlacement(Map<?, ?> event) {
        if (!(event.get("placement") instanceof Map<?, ?> placement)) {
            throw new PlanRevisionException("invalid_event", "a placement is a fixed-placement object");
        }
        List<String> unknown = JsonValue.namesOutside(placement, PLACEMENT_FIELDS);
        if (!unknown.isEmpty()) {
            throw new PlanRevisionException("invalid_event", "a placement does not carry " + CanonicalJson.spelling(unknown));
        }
        requireName(placement.get("item_type"), "placement.item_type");
        requireName(placement.get("container_type"), "placement.container_type");
        if (!(placement.get("orientation") instanceof String orientation)
                || !FixedPlacementShape.ORIENTATIONS.contains(orientation)) {
            throw new PlanRevisionException("invalid_event", "placement.orientation is one of the six codes");
        }
        Optional<Long> instance = placement.containsKey("container_instance")
                ? JsonValue.integer(placement.get("container_instance"))
                : Optional.of(1L);
        if (instance.isEmpty() || instance.get() < 1) {
            throw new PlanRevisionException("invalid_event", "placement.container_instance counts from 1");
        }
        Object position = placement.containsKey("position") ? placement.get("position") : Map.of();
        FixedPlacementShape.requirePoint(position, "placement.position", "",
                (message, field) -> new PlanRevisionException("invalid_event", message));
    }

    private static void requireName(Object value, String field) {
        if (!(value instanceof String name) || name.isEmpty()) {
            throw new PlanRevisionException("invalid_event", field + " is a non-empty string");
        }
    }

    // ----------------------------------------------------------------------------- applying

    private static Map<String, Object> applyAll(Object request, Object events) {
        if (!(request instanceof Map<?, ?>)) {
            throw new PlanRevisionException("invalid_revision", "a request is a JSON object");
        }
        if (!(events instanceof List<?> list)) {
            throw new PlanRevisionException("invalid_event", "events are a JSON array");
        }
        Map<String, Object> derived = thaw(request);
        PlacementIndex placements = new PlacementIndex();
        for (Object raw : list) {
            Map<?, ?> event = requireShape(raw);
            if (JsonValue.integer(event.get("sequence")).isEmpty()) {
                throw new PlanRevisionException("invalid_event", "event sequence "
                        + CanonicalJson.spelling(event.get("sequence")) + " is not an integer");
            }
            switch ((String) event.get("type")) {
                case "item_missing" -> applyItemMissing(derived, event);
                case "container_substituted" -> applyContainerSubstituted(derived, event);
                default -> placements.apply(derived, event);
            }
        }
        return derived;
    }

    private static void applyItemMissing(Map<String, Object> request, Map<?, ?> event) {
        List<Map<String, Object>> items = entries(request, "items");
        int index = indexOf(items, event.get("item_type"), "item");
        Map<String, Object> item = items.get(index);
        Optional<Long> quantity = item.containsKey("quantity") ? JsonValue.integer(item.get("quantity")) : Optional.of(1L);
        if (quantity.isEmpty()) {
            throw new PlanRevisionException("invalid_revision", "request.items[" + index + "].quantity is an integer");
        }
        long remaining = quantity.get() - JsonValue.integer(event.get("quantity")).orElseThrow();
        long fixed = fixedEntries(request).stream()
                .filter(entry -> Objects.equals(entry.get("item_type"), event.get("item_type"))).count();
        if (remaining < fixed) {
            throw new PlanRevisionException("event_conflict", "event " + sequenceOf(event) + ": " + fixed + " "
                    + event.get("item_type") + " are fixed, " + Math.max(remaining, 0) + " would remain");
        }
        if (remaining > 0) {
            item.put("quantity", remaining);
            return;
        }
        if (items.size() == 1) {
            throw new PlanRevisionException("event_conflict",
                    "event " + sequenceOf(event) + ": no item would remain to pack");
        }
        items.remove(index);
    }

    private static void applyContainerSubstituted(Map<String, Object> request, Map<?, ?> event) {
        List<Map<String, Object>> containers = entries(request, "containers");
        Object containerType = event.get("container_type");
        int index = indexOf(containers, containerType, "container");
        if (fixedEntries(request).stream().anyMatch(entry -> Objects.equals(entry.get("container_type"), containerType))) {
            throw new PlanRevisionException("event_conflict",
                    "event " + sequenceOf(event) + ": fixed placements are in " + containerType);
        }
        Map<?, ?> replacement = (Map<?, ?>) event.get("replacement");
        Object replacementId = replacement.get("id");
        for (int position = 0; position < containers.size(); position++) {
            if (position != index && Objects.equals(containers.get(position).get("id"), replacementId)) {
                throw new PlanRevisionException("event_conflict",
                        "event " + sequenceOf(event) + ": another container is already " + replacementId);
            }
        }
        containers.set(index, thaw(replacement));
    }

    /**
     * Per-replay index of the fixed placements already in the request, by canonical key, so a
     * repeated lock or verification is recognised in O(size of one placement) instead of by a scan.
     * The request's first-seen order stays untouched.
     */
    private static final class PlacementIndex {
        private List<Map<String, Object>> fixed;
        private final Set<String> keys = new HashSet<>();
        private boolean indexable = true;

        void apply(Map<String, Object> request, Map<?, ?> event) {
            if (fixed == null) {
                fixed = fixedEntries(request);
                request.put("fixed_placements", fixed);
                try {
                    for (Map<String, Object> entry : fixed) {
                        keys.add(canonicalJson(withDefaultInstance(entry)));
                    }
                } catch (PlanRevisionException unspellable) {
                    indexable = false;
                }
            }
            Map<String, Object> placement = withDefaultInstance((Map<?, ?>) event.get("placement"));
            if (indexable) {
                try {
                    if (!keys.add(canonicalJson(placement))) {
                        return;
                    }
                    fixed.add(thaw(event.get("placement")));
                    return;
                } catch (PlanRevisionException unspellable) {
                    indexable = false;
                }
            }
            // An uncanonicalizable value may occur in a direct applyEvents call. Keep the
            // reference's first-match and error order rather than make index construction an
            // earlier error.
            for (Map<String, Object> entry : fixed) {
                if (same(withDefaultInstance(entry), placement)) {
                    return;
                }
            }
            fixed.add(thaw(event.get("placement")));
        }

        private static Map<String, Object> withDefaultInstance(Map<?, ?> entry) {
            Map<String, Object> keyed = new LinkedHashMap<>();
            keyed.put("container_instance", 1L);
            keyed.putAll(thaw(entry));
            return keyed;
        }
    }

    private static List<Map<String, Object>> entries(Map<String, Object> request, String key) {
        if (!(request.get(key) instanceof List<?> list) || !list.stream().allMatch(entry -> entry instanceof Map)) {
            throw new PlanRevisionException("invalid_revision", "request." + key + " is a list of objects");
        }
        @SuppressWarnings("unchecked") // thaw builds every object of the tree as a Map<String, Object>.
        List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
        return typed;
    }

    /**
     * The request's fixed placements; absent or null is none, anything else not a list of objects
     * is a request no event can safely edit.
     */
    private static List<Map<String, Object>> fixedEntries(Map<String, Object> request) {
        if (request.get("fixed_placements") == null) {
            return new ArrayList<>();
        }
        return entries(request, "fixed_placements");
    }

    private static int indexOf(List<Map<String, Object>> entries, Object identifier, String kind) {
        for (int index = 0; index < entries.size(); index++) {
            if (Objects.equals(entries.get(index).get("id"), identifier)) {
                return index;
            }
        }
        throw new PlanRevisionException("event_conflict",
                "the request has no " + kind + " " + CanonicalJson.spelling(identifier));
    }

    private static long sequenceOf(Object event) {
        return JsonValue.integer(((Map<?, ?>) event).get("sequence")).orElseThrow();
    }

    // -------------------------------------------------------------------------------- audit

    private static long checkSequences(Map<?, ?> revision, int position, long last, List<RevisionIssue> issues) {
        List<?> events = (List<?>) revision.get("events");
        if (position == 0 && !events.isEmpty()) {
            issues.add(new RevisionIssue("sequence_gap", 0, "the root revision records no events"));
        }
        if (position > 0 && events.isEmpty()) {
            issues.add(new RevisionIssue("sequence_gap", position, "a revision records at least one event"));
        }
        long previous = last;
        for (Object event : events) {
            long sequence = sequenceOf(event);
            if (sequence != previous + 1) {
                issues.add(new RevisionIssue("sequence_gap", position,
                        "event " + sequence + " where " + (previous + 1) + " was next"));
            }
            previous = sequence;
        }
        return previous;
    }

    private static void checkRequest(Map<?, ?> revision, Map<?, ?> parent, int position, List<RevisionIssue> issues) {
        Map<String, Object> derived;
        try {
            derived = applyAll(parent.get("request"), revision.get("events"));
        } catch (PlanRevisionException error) {
            issues.add(new RevisionIssue("request_mismatch", position, "the events do not apply: " + error.getMessage()));
            return;
        }
        if (!same(derived, revision.get("request"))) {
            issues.add(new RevisionIssue("request_mismatch", position,
                    "the request is not what the parent's request and these events derive"));
        }
    }

    private static void checkArtifact(Map<?, ?> revision, Map<?, ?> parent, Object artifact, int position,
            List<RevisionIssue> issues) {
        Object approved = revision.get("approved");
        Optional<String> digest = digestOrEmpty(artifact);
        if (digest.isEmpty() || !(approved instanceof Map<?, ?> approval)
                || !Objects.equals(approval.get("artifact"), digest.get())) {
            issues.add(new RevisionIssue("artifact_mismatch", position,
                    "the artifact's digest is not the one this revision approved"));
            return;
        }
        Object provenance = artifact instanceof Map<?, ?> object ? object.get("provenance") : null;
        if (!(provenance instanceof Map<?, ?> origin) || !same(origin.get("request"), parent.get("request"))) {
            issues.add(new RevisionIssue("artifact_mismatch", position,
                    "the artifact was built from a different request than the parent's"));
        } else if (!same(origin.get("replay"), approval.get("replay"))) {
            issues.add(new RevisionIssue("artifact_mismatch", position,
                    "approved.replay is not the artifact's provenance.replay"));
        }
    }

    /** Equality as the canonical form sees it, so {@code 1.0} and {@code 1} are one value, as in every engine. */
    private static boolean same(Object left, Object right) {
        return canonicalJson(left).equals(canonicalJson(right));
    }

    /** An audited artifact's digest; one with no canonical form matches no approval. */
    private static Optional<String> digestOrEmpty(Object document) {
        try {
            return Optional.of(digest(document));
        } catch (PlanRevisionException unspellable) {
            return Optional.empty();
        }
    }

    /** A digest as itself, and anything a tampered chain puts in its place as JSON. */
    private static String plain(Object value) {
        return value instanceof String text ? text : CanonicalJson.spelling(value);
    }

    // ------------------------------------------------------------------------------- trees

    /** A deep, mutable copy of a JSON tree that keeps every key and its order. */
    @SuppressWarnings("unchecked") // A JSON object's keys are strings; the canonical writer judges any other.
    private static <T> T thaw(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, thaw(member)));
            return (T) copy;
        }
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>(elements.size());
            elements.forEach(element -> copy.add(thaw(element)));
            return (T) copy;
        }
        return (T) value;
    }

    /** A deep, unmodifiable copy, so a returned document cannot be changed under its digest. */
    @SuppressWarnings("unchecked") // Same shape as the tree it copies.
    private static <T> T frozen(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, frozen(member)));
            return (T) Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>(elements.size());
            elements.forEach(element -> copy.add(frozen(element)));
            return (T) Collections.unmodifiableList(copy);
        }
        return (T) value;
    }
}
