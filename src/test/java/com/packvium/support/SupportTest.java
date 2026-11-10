package com.packvium.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportTest {
    @Test
    void mulDivIsExactPastTheLongRange() {
        assertEquals(428_571_428_571_428_571L, Arithmetic.mulDiv(3_000_000_000L, 1_000_000_000L, 7));
        assertEquals(1_317_624_576_693_539_401L, Arithmetic.mulDiv(Long.MAX_VALUE, 1, 7));
        assertEquals(Long.MAX_VALUE / 3, Arithmetic.mulDiv(Long.MAX_VALUE, 2, 6));
        assertEquals(2, Arithmetic.mulDivCeil(3, 3, 5));
        assertEquals(0, Arithmetic.mulDivCeil(0, 3, 5));
    }

    @Test
    void sortKeysCompareLikePythonTuples() {
        assertTrue(SortKey.of(1L, "b").compareTo(SortKey.of(1L, "c")) < 0);
        assertTrue(SortKey.of(1L).compareTo(SortKey.of(1L, 0L)) < 0);
        assertTrue(SortKey.of(BigInteger.TEN.pow(30)).compareTo(SortKey.of(Long.MAX_VALUE)) > 0);
        assertTrue(SortKey.of(Double.NEGATIVE_INFINITY).compareTo(SortKey.of(Long.MIN_VALUE)) < 0);
        assertTrue(SortKey.of("￿").compareTo(SortKey.of("😀")) < 0);
    }

    @Test
    void stableSortKeepsInputOrderOnTies() {
        List<String> sorted = StableSorter.sortBy(List.of("b1", "a1", "b2", "a2"),
                value -> SortKey.of(value.substring(0, 1)));
        assertEquals(List.of("a1", "a2", "b1", "b2"), sorted);
    }

    @Test
    void fixedSixRoundsTheExactBinaryValue() {
        assertEquals("0.333333", Formats.fixed6(1.0 / 3));
        assertEquals("1.000000", Formats.fixed6(0.9999995));
        assertEquals("0.000000", Formats.fixed6(0));
    }
}
