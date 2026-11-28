package com.packvium.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Python's spelling of the values an issue detail prints; expectations are CPython 3.11's own. */
class PyFormatTest {
    @Test
    void scalarsAreSpeltAsPythonSpellsThem() {
        assertEquals("None", PyFormat.repr(null));
        assertEquals("True", PyFormat.repr(true));
        assertEquals("1e-05", PyFormat.repr(new BigDecimal("0.00001")));
        assertEquals("1.0", PyFormat.repr(new BigDecimal("1.0")));
        assertEquals("'x'", PyFormat.repr("x"));
        assertEquals("x", PyFormat.str("x"));
        assertEquals("(7,)", PyFormat.valueTuple(List.of(7L)));
        assertEquals("{'a': [1, None]}", PyFormat.repr(Map.of("a", java.util.Arrays.asList(1L, null))));
    }

    @Test
    void stringsEscapeWhatIsprintableRejects() {
        assertEquals("\"it's\"", PyFormat.string("it's"));
        assertEquals("'\\x85\\xa0\\u200b\\U000e0001\\ud800'", PyFormat.string("\u0085 ​󠀁\ud800"));
        assertEquals("'é中😀'", PyFormat.string("é中😀"));
    }

    @Test
    void fixedSixRoundsTheExactBinaryValueHalfToEven() {
        assertEquals("0.007812", PyFormat.fixed6(0.0078125));
        assertEquals("0.833333", PyFormat.fixed6(5.0 / 6.0));
        assertEquals("-0.000000", PyFormat.fixed6(-0.0));
    }

    @Test
    void aFractionBecomesTheNearestDouble() {
        assertEquals(0.1, Ratio.of(java.math.BigInteger.ONE, java.math.BigInteger.TEN).toDouble());
        assertEquals(1.0 / 3.0, Ratio.of(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(3)).toDouble());
        assertEquals(124L, ContainerCheck.ratioPpm(Units.ratioOf(new BigDecimal("0.0001245"))).longValue());
    }
}
