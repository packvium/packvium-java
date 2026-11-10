package com.packvium.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.UnpackedItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected sentences were produced by the PHP engine at tag v1.0.0. */
class ExplainTest {
    private static final ItemInstance A2 = new ItemInstance(Item.builder("A", Dimensions.mm(50, 50, 50)).build(), 2);
    private static final ItemInstance B1 = new ItemInstance(Item.builder("B", Dimensions.mm(50, 40, 30)).build(), 1);
    private static final UnpackedItem TIMED_OUT = new UnpackedItem(A2, "time_limit", List.of("a", "b"));

    @Test
    void rendersTheEvidenceLevelAndDetails() {
        assertEquals(List.of(
                "A#2: Unknown (limit reached): was not reached before the configured time limit expired (a; b)",
                "B#1: Proven: is forbidden from every offered container by a policy rule the request declared"
                        + " -- the rule and version are in the details",
                "B#1: Inferred: belongs to a group that could not all be placed together"),
                Explain.unpackedItems(List.of(TIMED_OUT, new UnpackedItem(B1, "policy_rule"),
                        new UnpackedItem(B1, "group_cannot_fit_together"))));
    }

    @Test
    void describesTheMessageForACallersCatalogue() {
        Map<String, Object> descriptor = Explain.unpackedItemDescriptor(TIMED_OUT);
        assertEquals(List.of("message_key", "arguments", "default_message"), List.copyOf(descriptor.keySet()));
        assertEquals("packvium.unpacked.time_limit", descriptor.get("message_key"));
        assertEquals(Map.of("item_id", "A#2", "evidence_level", "unknown_due_to_limit", "details", "a; b"),
                descriptor.get("arguments"));
        assertEquals("was not reached before the configured time limit expired", descriptor.get("default_message"));
    }

    @Test
    void refusesAnUnregisteredReason() {
        UnknownReasonException refusal = assertThrows(UnknownReasonException.class, () -> Explain.reason("nope"));
        assertEquals("no explanation registered for reason code 'nope'", refusal.getMessage());
        assertEquals("nope", refusal.reason());
    }
}
