package com.packvium.explain;

import com.packvium.domain.UnpackedItem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, localization-ready human-readable explanations for why an item was
 * not packed.
 *
 * <p>Rendering is driven entirely by {@code UnpackedItem.reason()} and {@code proof()} -- the
 * structured evidence already attached to every unpacked item -- never by matching or parsing
 * free text a caller would otherwise have to scrape out of a log. {@code REASON_MESSAGES} is the
 * one place English prose lives; a caller wanting another locale swaps that map (or calls
 * {@code reason()} against their own), not the code that walks the evidence.
 */
public final class Explain {
    /**
     * One deterministic English sentence per reason code. Every reason this library can
     * actually produce must have an entry here: {@code reason()} throws rather than silently
     * falling back to the bare code, so a new reason code introduced without a matching message
     * is caught immediately, not shipped mute.
     */
    public static final Map<String, String> REASON_MESSAGES = orderedMap(
            "no_compatible_container_dimensions",
            "does not fit inside any offered container in any rotation",
            "rotation_restricted",
            "would fit in some container with more rotations allowed, but not with the "
                    + "rotations this item permits",
            "payload_exceeded",
            "exceeds the maximum payload of every offered container",
            "policy_rule",
            "is forbidden from every offered container by a policy rule the request "
                    + "declared -- the rule and version are in the details",
            "no_eligible_container",
            "shares no eligible container tag with any offered container",
            "time_limit",
            "was not reached before the configured time limit expired",
            "effort_limit",
            "was not reached before the configured effort budget was exhausted",
            "group_cannot_fit_together",
            "belongs to a group that could not all be placed together",
            "insufficient_support",
            "would fit geometrically, but only by resting on support the minimum "
                    + "support ratio forbids",
            "no_feasible_placement",
            "found no feasible placement in the containers offered, for a reason the "
                    + "search could not further isolate",
            "search_exhausted",
            "was not placed before the configured search strategies were exhausted",
            "exact_search_incomplete",
            "was not placed because the exact search ended before proving a final answer",
            "container_inventory_exhausted",
            "requires another compatible container, but the declared inventory is exhausted");

    /**
     * Prefixes an explanation with what kind of evidence backs it, from
     * {@code ReasonProof.level()}: a caller deciding whether to trust "this will never
     * fit" (proven) versus "try again with more time" (unknown_due_to_limit) needs this
     * distinction as much as the sentence itself.
     */
    public static final Map<String, String> LEVEL_PREFIXES = orderedMap(
            "proven", "Proven",
            "unknown_due_to_limit", "Unknown (limit reached)",
            "observed", "Observed",
            "inferred", "Inferred");

    private Explain() {
    }

    /**
     * The bare English sentence for one reason code, with no item identity or evidence-level
     * prefix -- the building block {@code unpackedItem()} composes.
     *
     * @throws UnknownReasonException if no message is registered for {@code reason}
     */
    public static String reason(String reason) {
        String message = REASON_MESSAGES.get(reason);
        if (message == null) {
            throw new UnknownReasonException(reason);
        }
        return message;
    }

    /**
     * One deterministic sentence naming the item, its evidence level, and why it was not packed.
     * Any details already attached are appended verbatim, in order, not reformatted or
     * re-derived.
     */
    public static String unpackedItem(UnpackedItem item) {
        String sentence = reason(item.reason());
        String prefix = LEVEL_PREFIXES.getOrDefault(item.proof().level(), "");
        String lead = prefix.isEmpty() ? "" : prefix + ": ";
        String detail = item.details().isEmpty() ? "" : " (" + String.join("; ", item.details()) + ")";
        return item.instance().id() + ": " + lead + sentence + detail;
    }

    /** The message key, its arguments and the English default, for a caller's own catalogue. */
    public static Map<String, Object> unpackedItemDescriptor(UnpackedItem item) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("item_id", item.instance().id());
        arguments.put("evidence_level", item.proof().level());
        arguments.put("details", String.join("; ", item.details()));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("message_key", "packvium.unpacked." + item.reason());
        descriptor.put("arguments", Collections.unmodifiableMap(arguments));
        descriptor.put("default_message", reason(item.reason()));
        return Collections.unmodifiableMap(descriptor);
    }

    /**
     * {@code unpackedItem()} over every item, in the order given -- deterministic because the
     * input order is, and this makes no ordering decision of its own.
     */
    public static List<String> unpackedItems(List<UnpackedItem> items) {
        return items.stream().map(Explain::unpackedItem).toList();
    }

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
