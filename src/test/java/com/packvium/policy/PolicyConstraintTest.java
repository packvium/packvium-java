package com.packvium.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.Violation;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.serialization.JsonReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Expected results were produced by the PHP engine at tag v1.0.0. */
class PolicyConstraintTest {
    private static final PolicyConstraint CONSTRAINT = (PolicyConstraint) PolicyRuleSet.fromArray(JsonReader.parse("""
            {"as_of":0,"rules":[
             {"id":"cold","version":2,"effective_at":0,"priority":3,
              "require_container_tag":{"item_tag":"frozen","container_tag":"reefer"}},
             {"id":"sep","version":1,"effective_at":0,"priority":2,"separate_tags":{"tag":"acid","from_tag":"base"}},
             {"id":"cap","version":1,"effective_at":0,"priority":1,"limit_tag_per_container":{"tag":"x","max":2}}]}
            """)).constraints().get(0);
    private static final Container DRY = Container.builder("K", Dimensions.mm(100, 100, 100)).tags(List.of("dry")).build();

    @Test
    void rejectsWithTheCitedRule() {
        assertEquals("reject|policy_rule|cold@2: requires a container tagged 'reefer'", evaluate(List.of("frozen")));
        assertEquals("reject|policy_rule|sep@1: 'acid' may not share a container with 'base'",
                evaluate(List.of("acid"), List.of("base")));
        assertEquals("reject|policy_rule|sep@1: 'base' may not share a container with 'acid'",
                evaluate(List.of("base"), List.of("acid")));
        assertEquals("reject|policy_rule|cap@1: at most 2 item(s) tagged 'x' per container",
                evaluate(List.of("x"), List.of("x"), List.of("x")));
        assertEquals("allow|allowed|", evaluate(List.of("x"), List.of("x")));
        assertEquals("reject|policy_rule|cold@2: requires a container tagged 'reefer'",
                evaluate(List.of("acid", "frozen"), List.of("base")));
    }

    @Test
    void provesOnlyWhatTheRequestAloneDecides() {
        assertEquals(Optional.of(new Violation("policy_rule",
                        "cold@2: requires a container tagged 'reefer', which none of the containers offered carries")),
                CONSTRAINT.provesUnplaceable(instance(List.of("frozen"), "F"), List.of(DRY)));
        assertEquals(Optional.empty(), CONSTRAINT.provesUnplaceable(instance(List.of("acid"), "F"), List.of(DRY)));
    }

    @SafeVarargs
    private static String evaluate(List<String> tags, List<String>... present) {
        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < present.length; index++) {
            ItemInstance other = instance(present[index], "P" + index);
            Point at = new Point(index * 10L * 16000, 0, 0);
            placements.add(new Placement(other, at, Rotation.LWH, other.dimensions(), at, other.dimensions()));
        }
        ItemInstance item = instance(tags, "Q");
        ConstraintResult result = CONSTRAINT.evaluate(new ConstraintContext(DRY, placements, item,
                new Point(0, 50 * 16000, 0), Rotation.LWH, item.dimensions(), item.dimensions()));
        return (result.allowed() ? "allow" : "reject") + "|" + result.code() + "|" + result.detail();
    }

    private static ItemInstance instance(List<String> tags, String id) {
        return new ItemInstance(Item.builder(id, Dimensions.mm(10, 10, 10)).tags(tags).build(), 1);
    }
}
