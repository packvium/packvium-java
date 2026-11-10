package com.packvium.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.commerce.policy.Decision;
import com.packvium.commerce.policy.PolicyAction;
import com.packvium.commerce.policy.PolicyOperator;
import com.packvium.commerce.policy.PolicyRegistry;
import com.packvium.commerce.policy.PolicyScope;
import com.packvium.commerce.policy.Predicate;
import com.packvium.commerce.policy.RuleNotFoundException;
import com.packvium.commerce.policy.UnsupportedPredicateException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Expected decisions are the reference engines' answers to the v1.0.0 commerce corpus. */
class PolicyTest {
    private static final PolicyScope SCOPE = PolicyScope.CUSTOMER;

    private static Predicate equalsTo(Object value) {
        return new Predicate(SCOPE, "flag", PolicyOperator.EQUALS, Optional.of(value));
    }

    @Test
    void rejectOutranksAHigherPriorityAllow() {
        PolicyRegistry registry = new PolicyRegistry();
        registry.publish("allow", SCOPE, PolicyAction.ALLOW, List.of(equalsTo(1L)), 100, 0);
        registry.publish("deny", SCOPE, PolicyAction.REJECT, List.of(equalsTo(1L)), 1, 0, "no");
        Decision decision = registry.evaluate(SCOPE, Map.of("flag", 1L), 0);
        assertFalse(decision.allowed());
        assertEquals("deny", decision.citation().orElseThrow().ruleId());
    }

    @Test
    void equalPriorityBreaksOnTheSmallestCodePoint() {
        PolicyRegistry registry = new PolicyRegistry();
        // U+FFFF sorts below an astral code point, though its UTF-16 unit sorts above a surrogate.
        registry.publish("😀", SCOPE, PolicyAction.REJECT, List.of(equalsTo(1L)), 5, 0);
        registry.publish("￿", SCOPE, PolicyAction.REJECT, List.of(equalsTo(1L)), 5, 0);
        assertEquals("￿", registry.evaluate(SCOPE, Map.of("flag", 1L), 0).citation().orElseThrow().ruleId());
    }

    @Test
    void booleanEqualsTheIntegerItStandsFor() {
        assertTrue(equalsTo(true).matches(Map.of("flag", 1L)));
        assertTrue(equalsTo(0L).matches(Map.of("flag", false)));
        assertFalse(equalsTo(true).matches(Map.of("flag", 2L)));
        assertFalse(equalsTo(true).matches(Map.of("flag", "1")));
    }

    @Test
    void listEqualsTheObjectSpellingItsIndices() {
        Map<String, Object> indexed = new LinkedHashMap<>();
        indexed.put("0", 1L);
        indexed.put("1", 2L);
        assertTrue(equalsTo(List.of(1L, 2L)).matches(Map.of("flag", indexed)));
        assertFalse(equalsTo(Map.of("a", 1L)).matches(Map.of("flag", Map.of("b", 1L))));
    }

    @Test
    void neverEqualsANumberThatIsNotAnExactInteger() {
        assertFalse(equalsTo(new java.math.BigDecimal("1.5")).matches(Map.of("flag", new java.math.BigDecimal("1.5"))));
    }

    @Test
    void inOverAStringIsASubstringTest() {
        Predicate in = new Predicate(SCOPE, "flag", PolicyOperator.IN, Optional.of("hazmat-9"));
        assertTrue(in.matches(Map.of("flag", "mat")));
        assertTrue(in.matches(Map.of("flag", "")));
        assertFalse(in.matches(Map.of("flag", 9L)));
    }

    @Test
    void inOverANumberIsRefusedAtEvaluation() {
        Predicate in = new Predicate(SCOPE, "flag", PolicyOperator.IN, Optional.of(5L));
        UnsupportedPredicateException refused = assertThrows(
                UnsupportedPredicateException.class, () -> in.matches(Map.of("flag", 5L)));
        assertEquals("an in/not_in predicate needs a list or string value", refused.getMessage());
    }

    @Test
    void absentMatchesOnlyAMissingKey() {
        Predicate absent = new Predicate(SCOPE, "flag", PolicyOperator.ABSENT);
        Map<String, Object> nullValued = new LinkedHashMap<>();
        nullValued.put("flag", null);
        assertFalse(absent.matches(nullValued));
        assertTrue(absent.matches(Map.of()));
    }

    @Test
    void pinnedSnapshotsResolveInSortedOrder() {
        PolicyRegistry registry = new PolicyRegistry();
        registry.publish("b", SCOPE, PolicyAction.ALLOW, List.of(equalsTo(1L)), 1, 0);
        RuleNotFoundException missing = assertThrows(RuleNotFoundException.class, () -> registry.resolveVersions(
                List.of(new PolicyRegistry.Pin("z", 1), new PolicyRegistry.Pin("a", 1))));
        assertEquals("a", missing.ruleId());
        assertThrows(IllegalArgumentException.class, () -> registry.resolveVersions(
                List.of(new PolicyRegistry.Pin("b", 1), new PolicyRegistry.Pin("b", 1))));
    }

    @Test
    void openByDefault() {
        Decision decision = new PolicyRegistry().evaluate(SCOPE, Map.of(), 0);
        assertTrue(decision.allowed());
        assertTrue(decision.citation().isEmpty());
    }
}
