package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The summary, the item accounting, and the proof behind every unpacked reason. */
final class Accounting {
    private static final Set<String> PROVEN = Set.of("no_compatible_container_dimensions", "payload_exceeded",
            "no_eligible_container", "rotation_restricted", "policy_rule");
    private static final Set<String> LIMITED = Set.of("time_limit", "effort_limit");
    private static final Set<String> OBSERVED = Set.of("no_feasible_placement", "search_exhausted",
            "exact_search_incomplete", "insufficient_support");

    private Accounting() {
    }

    static void checkStructure(Object result, List<ResultIssue> issues) {
        Object summary = Json.at(result, "summary");
        List<?> containers = Json.items(Json.at(result, "containers"), "result.containers");
        long packed = 0;
        for (Object container : containers) {
            packed += Json.items(Json.at(container, "placements"), "placements").size();
        }
        long unpacked = Json.items(Json.at(result, "unpacked_items"), "result.unpacked_items").size();
        Map<String, Long> fields = new LinkedHashMap<>();
        fields.put("container_count", (long) containers.size());
        fields.put("packed_item_count", packed);
        fields.put("unpacked_item_count", unpacked);
        for (Map.Entry<String, Long> field : fields.entrySet()) {
            Object reported = Json.at(summary, field.getKey());
            if (!Json.equalsInt(reported, BigInteger.valueOf(field.getValue()))) {
                issues.add(new ResultIssue("summary_mismatch", field.getKey() + " " + PyFormat.str(reported) + " != "
                        + field.getValue()));
            }
        }
        Object complete = Json.at(result, "complete");
        if (!Json.equalsInt(complete, unpacked == 0 ? BigInteger.ONE : BigInteger.ZERO)) {
            issues.add(new ResultIssue("complete_mismatch", "complete=" + PyFormat.str(complete) + " with " + unpacked
                    + " unpacked"));
        }
    }

    /**
     * Participating {@code require_container_tag} rules, resolved from the request alone -- a
     * second implementation of the policy resolution, so a bug shared with an engine cannot hide
     * behind agreement with it.
     */
    static List<Object> containerTagRules(Object request) {
        Object policy = Json.get(request, "policy");
        if (!Json.isDict(policy)) {
            return List.of();
        }
        Object declared = Json.get(policy, "rules");
        if (!(declared instanceof List<?> rules) || rules.isEmpty()) {
            return List.of();
        }
        BigInteger asOf = Json.intInstance(Json.get(policy, "as_of"));
        if (asOf == null) {
            return List.of();
        }
        Object shipment = Json.get(policy, "shipment");
        Object facts = Json.truthy(shipment) ? shipment : Map.of();
        Ordered<Object> latest = new Ordered<>();
        for (Object rule : rules) {
            if (!Json.isDict(rule) || !Json.has(rule, "require_container_tag")) {
                continue;
            }
            Object appliesTo = Json.get(rule, "applies_to");
            appliesTo = Json.truthy(appliesTo) ? appliesTo : Map.of();
            if (!Json.isDict(appliesTo)) {
                throw MalformedResult.shape("applies_to must be an object");
            }
            if (!applies(Json.dict(appliesTo), facts)) {
                continue;
            }
            if (Json.compare(Json.get(rule, "effective_at", 0L), asOf) > 0) {
                continue;
            }
            Object id = Json.get(rule, "id");
            Object current = latest.get(id);
            boolean newer = current == null || Json.compare(
                    List.of(Json.get(rule, "effective_at", 0L), Json.get(rule, "version", 0L)),
                    List.of(Json.get(current, "effective_at", 0L), Json.get(current, "version", 0L))) > 0;
            if (newer) {
                latest.set(id, rule);
            }
        }
        return latest.values();
    }

    private static boolean applies(Map<?, ?> appliesTo, Object shipment) {
        for (Map.Entry<?, ?> fact : appliesTo.entrySet()) {
            if (!Json.equals(Json.get(shipment, String.valueOf(fact.getKey())), fact.getValue())) {
                return false;
            }
        }
        return true;
    }

    static void checkAccounting(Object result, Context context, List<ResultIssue> issues) {
        Ordered<Boolean> seen = new Ordered<>();
        Ordered<Long> perType = new Ordered<>();
        for (Object container : Json.items(Json.at(result, "containers"), "result.containers")) {
            for (Object placement : Json.items(Json.at(container, "placements"), "placements")) {
                Object itemId = Json.at(placement, "item_id");
                if (seen.has(itemId)) {
                    issues.add(new ResultIssue("duplicate_item", PyFormat.str(itemId)));
                }
                seen.set(itemId, true);
                Object itemType = Json.at(placement, "item_type");
                perType.set(itemType, perType.get(itemType, 0L) + 1);
            }
        }
        Object algorithm = Json.at(result, "algorithm");
        boolean timedOut = Json.truthy(Json.at(algorithm, "time_limit_reached"));
        boolean effortLimited = Json.truthy(Json.at(algorithm, "effort_limit_reached"));
        for (Object unpacked : Json.items(Json.at(result, "unpacked_items"), "result.unpacked_items")) {
            Object itemId = Json.at(unpacked, "item_id");
            if (seen.has(itemId)) {
                issues.add(new ResultIssue("duplicate_item", PyFormat.str(itemId) + " is both packed and unpacked"));
            }
            seen.set(itemId, true);
            Object itemType = Json.at(unpacked, "item_type");
            perType.set(itemType, perType.get(itemType, 0L) + 1);
            if (!Json.truthy(Json.get(unpacked, "reason"))) {
                issues.add(new ResultIssue("missing_reason", PyFormat.str(itemId)));
            }
            checkReasonProof(unpacked, context.items().get(itemType), context, timedOut, effortLimited, issues);
        }
        for (int index = 0; index < perType.size(); index++) {
            Object itemType = perType.keys().get(index);
            long count = perType.values().get(index);
            ItemSpec spec = context.items().get(itemType);
            if (spec == null) {
                issues.add(new ResultIssue("unknown_item", PyFormat.str(itemType)));
            } else if (!Json.equalsInt(spec.quantity(), BigInteger.valueOf(count))) {
                issues.add(new ResultIssue("quantity_mismatch", PyFormat.str(itemType) + ": " + count + " accounted for, "
                        + PyFormat.str(spec.quantity()) + " requested"));
            }
        }
        for (int index = 0; index < context.items().size(); index++) {
            Object itemType = context.items().keys().get(index);
            if (!perType.has(itemType)) {
                issues.add(new ResultIssue("quantity_mismatch", PyFormat.str(itemType) + ": 0 accounted for, "
                        + PyFormat.str(context.items().values().get(index).quantity()) + " requested"));
            }
        }
    }

    /**
     * Whether one envelope of this size fits the empty container: its frame and, for a flat bag,
     * both girth sums with the envelope alone at the origin.
     */
    static boolean fitsEmpty(ContainerSpec container, List<BigInteger> size) {
        for (int axis = 0; axis < 3; axis++) {
            if (size.get(axis).compareTo(container.inner().get(axis)) > 0) {
                return false;
            }
        }
        return !container.flatBag() || (size.get(0).add(size.get(2)).compareTo(container.inner().get(0)) <= 0
                && size.get(1).add(size.get(2)).compareTo(container.inner().get(1)) <= 0);
    }

    /**
     * Whether some rotated envelope fits some container, stopping at the first fit as Python's
     * generator does, so an unknown rotation is only an error when the search reaches it.
     */
    private static boolean fitsSome(ItemSpec spec, Context context, List<?> rotations) {
        BigInteger margin = context.clearance().multiply(Exact.TWO);
        for (ContainerSpec container : context.containers().values()) {
            for (Object rotation : rotations) {
                int[] axes = Geometry.axes(Json.text(rotation, "a rotation"));
                List<BigInteger> size = List.of(spec.dimensions().get(axes[0]).add(margin),
                        spec.dimensions().get(axes[1]).add(margin), spec.dimensions().get(axes[2]).add(margin));
                if (fitsEmpty(container, size)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code value in tags} for a frozenset of strings; an unhashable value is Python's {@code TypeError}. */
    static boolean holds(Set<String> tags, Object value) {
        Json.hashKey(value);
        return value instanceof String text && tags.contains(text);
    }

    private static void checkReasonProof(Object unpacked, ItemSpec spec, Context context, boolean timedOut,
            boolean effortLimited, List<ResultIssue> issues) {
        String itemId = PyFormat.str(Json.get(unpacked, "item_id", "?"));
        Object reason = Json.get(unpacked, "reason", "");
        Object proof = Json.get(unpacked, "proof");
        if (!Json.isDict(proof)) {
            issues.add(new ResultIssue("missing_reason_proof", itemId));
            return;
        }
        Json.hashKey(reason);
        String expected = in(reason, PROVEN) ? "proven" : in(reason, LIMITED) ? "unknown_due_to_limit"
                : in(reason, OBSERVED) ? "observed" : "inferred";
        Object level = Json.get(proof, "level");
        if (!Json.isText(level, expected)) {
            issues.add(new ResultIssue("reason_proof_mismatch", itemId + ": " + PyFormat.repr(reason) + " has proof "
                    + PyFormat.repr(level) + ", expected " + PyFormat.string(expected)));
        }
        if (Json.isText(reason, "time_limit") && !timedOut) {
            issues.add(new ResultIssue("reason_limit_mismatch", itemId + ": time_limit reason without a reached time limit"));
        }
        if (Json.isText(reason, "effort_limit") && !effortLimited) {
            issues.add(new ResultIssue("reason_limit_mismatch", itemId + ": effort_limit reason without a reached effort limit"));
        }
        if (spec != null) {
            checkProvenReason(unpacked, reason, spec, context, itemId, issues);
        }
        checkObservations(proof, reason, itemId, issues);
    }

    private static boolean in(Object reason, Set<String> names) {
        return reason instanceof String text && names.contains(text);
    }

    private static void checkProvenReason(Object unpacked, Object reason, ItemSpec spec, Context context, String itemId,
            List<ResultIssue> issues) {
        List<String> every = Geometry.ORIENTATIONS;
        if (Json.isText(reason, "no_compatible_container_dimensions")) {
            if (fitsSome(spec, context, spec.allowedRotations())) {
                issues.add(new ResultIssue("invalid_proven_reason", itemId + ": at least one allowed orientation fits a container"));
            }
            if (fitsSome(spec, context, every)) {
                issues.add(new ResultIssue("invalid_proven_reason", itemId + ": fits at least one container once every "
                        + "rotation is tried, not only its own allowed set -- should be rotation_restricted"));
            }
        }
        if (Json.isText(reason, "rotation_restricted")) {
            if (fitsSome(spec, context, spec.allowedRotations())) {
                issues.add(new ResultIssue("invalid_proven_reason", itemId + ": already fits a container within its own "
                        + "allowed rotations"));
            }
            if (!fitsSome(spec, context, every)) {
                issues.add(new ResultIssue("invalid_proven_reason", itemId + ": does not fit any container even with "
                        + "every rotation tried -- should be no_compatible_container_dimensions"));
            }
        }
        if (Json.isText(reason, "payload_exceeded")) {
            for (ContainerSpec container : context.containers().values()) {
                if (container.maxPayload() == null || spec.weight().compareTo(container.maxPayload()) <= 0) {
                    issues.add(new ResultIssue("invalid_proven_reason", itemId + ": its weight is within at least one "
                            + "container payload limit"));
                    break;
                }
            }
        }
        if (Json.isText(reason, "no_eligible_container")) {
            for (ContainerSpec container : context.containers().values()) {
                if (spec.eligibleContainerTags().isEmpty()
                        || !ContainerCheck.disjoint(spec.eligibleContainerTags(), container.tags())) {
                    issues.add(new ResultIssue("invalid_proven_reason", itemId + ": at least one container satisfies its "
                            + "eligible tags"));
                    break;
                }
            }
        }
        if (Json.isText(reason, "policy_rule")) {
            checkPolicyCitation(unpacked, spec, context, itemId, issues);
        }
    }

    /**
     * Two claims, both checked: that some rule rules this item out of every container, and that
     * the rule the result names is that rule.
     */
    private static void checkPolicyCitation(Object unpacked, ItemSpec spec, Context context, String itemId,
            List<ResultIssue> issues) {
        List<Object> blocking = new ArrayList<>();
        for (Object rule : context.rules()) {
            Object requirement = Json.at(rule, "require_container_tag");
            if (!holds(spec.tags(), Json.get(requirement, "item_tag"))) {
                continue;
            }
            boolean admitted = false;
            for (ContainerSpec container : context.containers().values()) {
                if (holds(container.tags(), Json.get(Json.at(rule, "require_container_tag"), "container_tag"))) {
                    admitted = true;
                    break;
                }
            }
            if (!admitted) {
                blocking.add(rule);
            }
        }
        if (blocking.isEmpty()) {
            issues.add(new ResultIssue("invalid_proven_reason", itemId + ": no participating policy rule rules it out of "
                    + "every container"));
            return;
        }
        Object details = Json.get(unpacked, "details");
        String cited = "";
        if (Json.truthy(details)) {
            List<String> parts = new ArrayList<>();
            for (Object detail : Json.items(details, "details")) {
                parts.add(Json.text(detail, "details"));
            }
            cited = String.join(" ", parts);
        }
        for (Object rule : blocking) {
            String name = PyFormat.str(Json.at(rule, "id")) + "@" + PyFormat.str(Json.at(rule, "version"));
            if (cited.contains(name)) {
                return;
            }
        }
        issues.add(new ResultIssue("invalid_proven_reason", itemId + ": cites " + PyFormat.string(cited)
                + ", which names no rule that actually blocks it"));
    }

    private static void checkObservations(Object proof, Object reason, String itemId, List<ResultIssue> issues) {
        Object observations = Json.get(proof, "observations");
        if (!(observations instanceof List<?> list) || list.isEmpty()) {
            issues.add(new ResultIssue("missing_reason_observations", itemId));
            return;
        }
        boolean observedReason = false;
        for (Object observation : list) {
            if (!Json.isDict(observation)) {
                issues.add(new ResultIssue("malformed_reason_observation", itemId));
                continue;
            }
            Object code = Json.get(observation, "code");
            BigInteger count = Json.intInstance(Json.get(observation, "count"));
            Object details = Json.get(observation, "details");
            if (!(code instanceof String text) || text.isEmpty() || count == null || count.signum() < 1
                    || !Json.isList(details)) {
                issues.add(new ResultIssue("malformed_reason_observation", itemId));
                continue;
            }
            if (Json.equals(code, reason)) {
                observedReason = true;
            }
        }
        if (!observedReason) {
            issues.add(new ResultIssue("reason_observation_mismatch", itemId + ": " + PyFormat.str(reason)));
        }
    }
}
