package com.packvium.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Expected values were produced by the Python reference engine at tag v1.0.0. */
class UnitsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "12 3/8 in|NEAREST|5029200",
        "3/16|NEAREST|3000",
        "1.123456789012345678|FLOOR|17975",
        "1.123456789012345678|CEIL|17976",
        "1.123456789012345678|NEAREST|17975",
        "10cm|NEAREST|1600000",
        "5 inches|NEAREST|2032000",
        "2 ft|NEAREST|9753600",
        "7.5|NEAREST|120000",
        "100 ticks|NEAREST|100",
        "0.0000001|FLOOR|0",
        "0.0000001|CEIL|1",
        "0.0000001|NEAREST|0",
        "1/3|FLOOR|5333",
        "1/3|CEIL|5334",
        "1/3|NEAREST|5333",
    })
    void parsesLengthsLikeTheReference(String written, Rounding rounding, long ticks) {
        assertEquals(ticks, Length.parse(written, "mm", rounding).ticks());
    }

    @Test
    void takesTheSignFromTheWrittenText() {
        assertThrows(IllegalArgumentException.class, () -> Length.parse("-0 1/2", "mm"));
    }

    @Test
    void readsTheObjectSpelling() {
        assertEquals(2032000, Length.parse(Map.of("value", "5", "unit", "in"), "mm").ticks());
        assertEquals(80000, Length.parse(Map.of("value", 5L), "mm").ticks());
    }

    @Test
    void rejectsFloatInput() {
        assertThrows(IllegalArgumentException.class, () -> Length.parse(1.5d, "mm"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1|in|0.00000246",
        "1219201|in|3.00000246",
        "5|mm|0.0003125",
        "8|mm|0.0005",
        "24|mm|0.0015",
        "120000|cm|0.75",
        "123456789|ft|25.31512242",
        "0|mm|0",
    })
    void rendersLengthDecimalsLikeTheReference(long ticks, String unit, String expected) {
        assertEquals(expected, new Length(ticks).decimal(unit));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1.5 kg|12000000000",
        "3 lb|10886216880",
        "2oz|453592370",
        "250 mg|2000000",
        "1/3 g|2666667",
    })
    void parsesWeightsLikeTheReference(String written, long ticks) {
        assertEquals(ticks, Weight.parse(written, "g").ticks());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1|kg|0",
        "3628738960|lb|1",
        "5|oz|0.00000002",
        "4|mg|0.0005",
        "12|mg|0.0015",
    })
    void rendersWeightDecimalsLikeTheReference(long ticks, String unit, String expected) {
        assertEquals(expected, new Weight(ticks).decimal(unit));
    }

    @Test
    void refusesUnknownUnits() {
        assertThrows(IllegalArgumentException.class, () -> Length.of("1", "yd", Rounding.NEAREST));
        assertThrows(IllegalArgumentException.class, () -> Weight.of("1", "st", Rounding.NEAREST));
    }
}
