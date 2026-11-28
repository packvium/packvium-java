package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Expected sequences were produced by the PHP engine at tag v1.0.0. */
class DeterministicRandomTest {
    @Test
    void drawsTheReferenceSequence() {
        DeterministicRandom random = new DeterministicRandom(42);
        List<Long> draws = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            draws.add(random.nextInt(7));
        }
        assertEquals(List.of(0L, 6L, 1L, 6L, 5L, 4L), draws);
    }

    @Test
    void shufflesLikeTheReference() {
        List<Integer> shuffled = new DeterministicRandom(7).shuffled(IntStream.range(0, 10).boxed().toList());
        assertEquals(List.of(6, 1, 4, 9, 8, 2, 7, 3, 5, 0), shuffled);
    }

    /** A negative seed reduces to its residue modulo 2^31, as in the reference engine (tag v1.7.0). */
    @Test
    void aNegativeSeedShufflesAsTheReferenceDoes() {
        List<Integer> values = IntStream.range(0, 10).boxed().toList();
        assertEquals(List.of(5, 0, 3, 4, 8, 7, 6, 9, 2, 1), new DeterministicRandom(-7).shuffled(values));
        assertEquals(new DeterministicRandom((1L << 31) - 7).shuffled(values), new DeterministicRandom(-7).shuffled(values));
    }
}
