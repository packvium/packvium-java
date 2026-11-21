package com.packvium.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.serialization.JsonReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RFC 8785 canonical JSON, held to {@code packvium._canonical_json} at v1.3.0; the cases are
 * PHP's CanonicalJsonTest, and every expected spelling is the tag's Python output.
 *
 * <p>What is checked is where the language defaults disagree, because that is where a
 * cross-language byte comparison fails: integral floats, exponents, digits that are not the
 * shortest, key order outside the Basic Multilingual Plane, the characters JSON writers escape
 * differently and the values no engine can carry exactly.
 */
class CanonicalJsonTest {
    private static final Object[][] NUMBERS = {
        {0.25, "0.25"}, {1.0, "1"}, {-0.0, "0"}, {4.5, "4.5"}, {0.1, "0.1"}, {2e-3, "0.002"},
        {0.000001, "0.000001"}, {1e-7, "1e-7"}, {1.5e-7, "1.5e-7"}, {1e-27, "1e-27"}, {-1.5, "-1.5"},
        {123456789.5, "123456789.5"}, {333333333.33333329, "333333333.3333333"},
        {9007199254740991.0, "9007199254740991"}, {5e-324, "5e-324"}, {100.0, "100"},
        {Math.pow(2, 52), "4503599627370496"}, {1125899906842624.25, "1125899906842624.2"},
        {Double.MIN_NORMAL, "2.2250738585072014e-308"},
        // Powers of two whose correctly rounded 16-digit decimal misses the double while the grid
        // point on the other side hits: rounding to the nearest candidate alone writes 17 digits.
        {Math.pow(2, -1017), "7.120236347223045e-307"}, {Math.pow(2, -1007), "7.291122019556398e-304"},
        {Math.pow(2, -957), "8.209073602596753e-289"}, {Math.pow(2, -921), "5.641232424577593e-278"},
        {Math.pow(2, -808), "5.858190679279809e-244"},
    };

    @Test
    void shortestDigitsAreDefinedOnlyForAFinitePositiveDouble() {
        assertEquals(new ShortestFloat.Digits("25", 0), ShortestFloat.digits(0.25));
        assertEquals(new ShortestFloat.Digits("1", -6), ShortestFloat.digits(1e-7));
        assertEquals(new ShortestFloat.Digits("5", -323), ShortestFloat.digits(Double.MIN_VALUE));
        for (double value : new double[] {0.0, -0.0, -1.5, Double.POSITIVE_INFINITY, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> ShortestFloat.digits(value));
        }
    }

    @Test
    void numbersAreWrittenAsEcmascriptWritesThem() {
        for (Object[] number : NUMBERS) {
            assertEquals(number[1], CanonicalJson.encode(number[0]), "for " + number[0]);
        }
    }

    @Test
    void aDecodedFractionIsTheNearestDouble() {
        // The reference engines decode every non-integer JSON number to a double.
        assertEquals("[1,100,0.1,1e-7]", CanonicalJson.encode(JsonReader.parse("[1.0,1E2,0.1000000000000000055511,1e-7]")));
        assertEquals("[0.5]", CanonicalJson.encode(List.of(new BigDecimal("0.5"))));
    }

    @Test
    void integersAndLiteralsKeepTheirSpelling() {
        assertEquals("[0,-7,9007199254740991,-9007199254740991,true,false,null,3]",
                CanonicalJson.encode(Arrays.asList(0L, -7L, 9007199254740991L, -9007199254740991L, true, false, null, 3)));
        assertEquals("9007199254740991", CanonicalJson.encode(BigInteger.valueOf(9007199254740991L)));
    }

    @Test
    void keysAreSortedByUtf16CodeUnitsNotCodePoints() {
        // U+FFFD sorts after U+1F600 by code point and before it by UTF-16 code unit, whose first
        // unit is the high surrogate U+D83D. JavaScript sorts the second way.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("�", 1L);
        value.put("😀", 2L);
        value.put("a", 0L);
        value.put("", 3L);
        assertEquals("{\"a\":0,\"😀\":2,\"\":3,\"�\":1}", CanonicalJson.encode(value));
    }

    @Test
    void onlyTheCharactersRfc8785NamesAreEscaped() {
        String text = "\"\\\b\t\n\f\r\u0000\u001f/\u007f  é📦";
        String expected = "\"" + "\\\"" + "\\\\" + "\\b" + "\\t" + "\\n" + "\\f" + "\\r" + "\\u0000" + "\\u001f"
                + "/\u007f  é📦" + "\"";
        assertEquals(expected, CanonicalJson.encode(text));
    }

    @Test
    void thereIsNoWhitespaceAndNestingIsPreserved() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("d", List.of());
        inner.put("c", Map.of());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", List.of(1L, inner));
        value.put("a", "x");
        assertEquals("{\"a\":\"x\",\"b\":[1,{\"c\":{},\"d\":[]}]}", CanonicalJson.encode(value));
    }

    @Test
    void decodedObjectsKeepEmptyObjectsAndNumericNames() {
        String text = "{\"0\":\"a\",\"1\":{},\"e\":[],\"n\":{\"9\":2,\"10\":1},\"z\":{\"0\":[{}]}}";
        assertEquals("{\"0\":\"a\",\"1\":{},\"e\":[],\"n\":{\"10\":1,\"9\":2},\"z\":{\"0\":[{}]}}",
                CanonicalJson.encode(JsonReader.parse(text)));
    }

    @Test
    void aNumberNoEngineHoldsExactlyIsRefused() {
        List<Object> values = List.of(9007199254740992L, -9007199254740992L, Long.MAX_VALUE,
                BigInteger.TWO.pow(64), 9007199254740992.0, 1e21, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN, new BigDecimal("1e400"));
        for (Object value : values) {
            assertEquals("number_out_of_range", code(Map.of("n", value)), "for " + value);
        }
    }

    @Test
    void aLoneSurrogateIsRefusedInAValueAndInAKey() {
        for (String text : List.of("\ud800", "a\udc00", "\ude00\ud83d", "\ud83d")) {
            assertEquals("invalid_string", code(text));
            assertEquals("invalid_string", code(Map.of(text, 1L)));
        }
    }

    @Test
    void aValueJsonCannotSpellIsRefused() {
        Map<Object, Object> numericKey = new LinkedHashMap<>();
        numericKey.put(1L, "a");
        for (Object value : List.of(new Object(), Optional.empty(), Map.of("a", new StringBuilder()), numericKey)) {
            assertEquals("invalid_value", code(value));
        }
    }

    private static String code(Object value) {
        return assertThrows(CanonicalJsonException.class, () -> CanonicalJson.encode(value)).errorCode();
    }

    /** The refusal quotes the value as Python's {@code repr} does, since revision messages are compared. */
    @Test
    void anOutOfRangeFloatIsQuotedAsTheReferenceQuotesIt() {
        double[] values = {1e300, 9007199254740994.0, 1e16, 1.2345678901234568e17, -1e22};
        String[] quoted = {"1e+300", "9007199254740994.0", "1e+16", "1.2345678901234568e+17", "-1e+22"};
        for (int index = 0; index < values.length; index++) {
            double value = values[index];
            CanonicalJsonException refusal = assertThrows(
                    CanonicalJsonException.class, () -> CanonicalJson.encode(value));
            assertEquals(quoted[index] + " is beyond what every engine holds exactly",
                    refusal.getMessage());
        }
        assertEquals(List.of("1.5e-07", "0.0001", "1e-05", "inf"),
                List.of(PythonRepr.floating(1.5e-7), PythonRepr.floating(0.0001), PythonRepr.floating(0.00001),
                        PythonRepr.floating(Double.POSITIVE_INFINITY)));
    }
}
