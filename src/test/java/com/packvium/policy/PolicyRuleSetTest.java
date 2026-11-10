package com.packvium.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.serialization.JsonReader;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every expected rule order and refusal message was produced by the PHP engine at tag v1.0.0. */
class PolicyRuleSetTest {
    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("null", "ok", ""),
            Arguments.of("5", "err", "policy must be an object"),
            Arguments.of("[]", "ok", ""),
            Arguments.of("{\"x\":1,\"b\":2,\"a\":3}", "err", "policy has unknown keys: a, b, x"),
            Arguments.of("{\"rules\":{}}", "ok", ""),
            Arguments.of("{\"rules\":5}", "err", "policy.rules must be an array"),
            Arguments.of("{\"rules\":[{\"id\":\"a\"}]}", "err", "policy.as_of is required whenever policy.rules is non-empty"),
            Arguments.of("{\"as_of\":-1,\"rules\":[{}]}", "err", "policy.as_of must be an integer >= 0"),
            Arguments.of("{\"as_of\":true,\"rules\":[{}]}", "err", "policy.as_of must be an integer >= 0"),
            Arguments.of("{\"as_of\":1.0,\"rules\":[{}]}", "err", "policy.as_of must be an integer >= 0"),
            Arguments.of("{\"as_of\":0,\"rules\":[5]}", "err", "policy.rules[0] must be an object"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"a\"}]}", "err", "policy.rules[0] must name exactly one rule form (limit_tag_per_container, require_container_tag, separate_tags), not 0"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"separate_tags\":{},\"require_container_tag\":{}}]}", "err", "policy.rules[0] must name exactly one rule form (limit_tag_per_container, require_container_tag, separate_tags), not 2"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"separate_tags\":{\"tag\":\"a\",\"from_tag\":\"b\"}}]}", "err", "policy.rules[0].id must be a non-empty string"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"\",\"separate_tags\":{}}]}", "err", "policy.rules[0].id must be a non-empty string"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{}}]}", "err", "policy.rules[0].separate_tags is missing tag, from_tag"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":0,\"separate_tags\":{}}]}", "err", "policy.rules[0].version must be an integer >= 1"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":null}]}", "err", "policy.rules[0].separate_tags must be an object"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{\"z\":1,\"10\":2,\"9\":3,\"tag\":\"a\"}}]}", "err", "policy.rules[0].separate_tags has unknown keys: 9, 10, z"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{\"tag\":\"a\"}}]}", "err", "policy.rules[0].separate_tags is missing from_tag"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{\"tag\":\"a\",\"from_tag\":\"\"}}]}", "err", "policy.rules[0].separate_tags.from_tag must be a non-empty string"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"limit_tag_per_container\":{\"tag\":\"a\",\"max\":-1}}]}", "err", "policy.rules[0].limit_tag_per_container.max must be an integer >= 0"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"applies_to\":{\"zone\":\"a\",\"customer\":\"x\"},\"limit_tag_per_container\":{\"tag\":\"a\",\"max\":1}}]}", "err", "policy.rules[0].applies_to names unknown shipment facts: zone"),
            Arguments.of("{\"as_of\":0,\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"applies_to\":{\"customer\":\"\"},\"limit_tag_per_container\":{\"tag\":\"a\",\"max\":1}}]}", "err", "policy.rules[0].applies_to.customer must be a non-empty string"),
            Arguments.of("{\"as_of\":0,\"shipment\":\"x\",\"rules\":[{\"id\":\"r\",\"version\":1,\"effective_at\":0,\"priority\":0,\"limit_tag_per_container\":{\"tag\":\"a\",\"max\":1}}]}", "err", "policy.shipment must be an object"),
            Arguments.of("{\"as_of\":100,\"shipment\":{\"customer\":\"acme\"},\"rules\":[ {\"id\":\"b\",\"version\":1,\"effective_at\":0,\"priority\":1,\"separate_tags\":{\"tag\":\"x\",\"from_tag\":\"y\"}}, {\"id\":\"a\",\"priority\":1,\"version\":1,\"effective_at\":10,\"require_container_tag\":{\"item_tag\":\"x\",\"container_tag\":\"cold\"}}, {\"id\":\"a\",\"priority\":1,\"version\":2,\"effective_at\":10,\"require_container_tag\":{\"item_tag\":\"x\",\"container_tag\":\"frozen\"}}, {\"id\":\"a\",\"priority\":1,\"version\":3,\"effective_at\":200,\"require_container_tag\":{\"item_tag\":\"x\",\"container_tag\":\"never\"}}, {\"id\":\"c\",\"version\":1,\"effective_at\":0,\"priority\":5,\"applies_to\":{\"customer\":\"acme\"},\"limit_tag_per_container\":{\"tag\":\"x\",\"max\":2}}, {\"id\":\"d\",\"version\":1,\"effective_at\":0,\"priority\":9,\"applies_to\":{\"customer\":\"other\"},\"limit_tag_per_container\":{\"tag\":\"x\",\"max\":2}}, {\"id\":\"10\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{\"tag\":\"p\",\"from_tag\":\"q\"}}, {\"id\":\"9\",\"version\":1,\"effective_at\":0,\"priority\":0,\"separate_tags\":{\"tag\":\"p\",\"from_tag\":\"q\"}} ]}", "ok", "c@1/LimitTagPerContainer,a@2/RequireContainerTag,b@1/SeparateTags,9@1/SeparateTags,10@1/SeparateTags"));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void parsesAndResolvesLikeTheReference(String json, String kind, String expected) {
        Object raw = JsonReader.parse(json);
        if (kind.equals("err")) {
            PolicyException refusal = assertThrows(PolicyException.class, () -> PolicyRuleSet.fromArray(raw));
            assertEquals(expected, refusal.getMessage());
            return;
        }
        String resolved = PolicyRuleSet.fromArray(raw).rules().stream()
                .map(rule -> rule.citation() + "/" + rule.form().getClass().getSimpleName())
                .collect(Collectors.joining(","));
        assertEquals(expected, resolved);
    }
}
