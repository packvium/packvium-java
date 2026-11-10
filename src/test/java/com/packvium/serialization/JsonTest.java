package com.packvium.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void keepsIntegersExactAndNeverProducesADouble() {
        List<?> values = (List<?>) JsonReader.parse("[9007199254740993, 92233720368547758070, 1.5, -0]");
        assertEquals(9007199254740993L, values.get(0));
        assertInstanceOf(BigInteger.class, values.get(1));
        assertInstanceOf(BigDecimal.class, values.get(2));
        assertEquals(0L, values.get(3));
    }

    @Test
    void aRepeatedKeyKeepsItsFirstPositionAndLastValue() {
        Map<?, ?> object = (Map<?, ?>) JsonReader.parse("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals(List.of("a", "b"), List.copyOf(object.keySet()));
        assertEquals(3L, object.get("a"));
    }

    @Test
    void refusesWhatRfc8259Refuses() {
        for (String invalid : List.of("01", "1.", ".5", "[1,]", "{\"a\" 1}", "\"\u0001\"", "nul", "1 2")) {
            assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(invalid), invalid);
        }
    }

    @Test
    void roundTripsEscapesAndAstralCharacters() {
        String text = "{\"k\":\"a\\\"b\\\\c\\n\\u0001\\ud83d\\ude00\\ud800\"}";
        Object parsed = JsonReader.parse(text);
        assertEquals(parsed, JsonReader.parse(JsonWriter.write(parsed)));
    }

    @Test
    void namesEveryUnsupportedFieldOnceInSortedOrder() {
        UnsupportedFields guard = new UnsupportedFields(List.of("policy"), List.of(), List.of("zeta"), List.of(),
                List.of("convex_hull"));
        Map<?, ?> request = (Map<?, ?>) JsonReader.parse(
                "{\"policy\":{},\"items\":[{\"zeta\":1,\"shape_type\":\"convex_hull\"},{\"zeta\":2}]}");
        UnsupportedFeatureException refusal = assertThrows(UnsupportedFeatureException.class, () -> guard.reject(request));
        assertEquals("unsupported_feature: the Java engine does not yet implement item.shape_type=convex_hull, "
                + "item.zeta, policy; the request was rejected instead of silently ignoring public fields",
                refusal.getMessage());
    }
}
