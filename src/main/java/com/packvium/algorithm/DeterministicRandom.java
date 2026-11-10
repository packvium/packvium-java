package com.packvium.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Seeded linear congruential generator, identical in every engine.
 *
 * <p>Only the high bits are published: the low bits of a power-of-two modulus have tiny
 * periods, and the shuffle asks for very small bounds. Draws outside the largest whole
 * multiple of the bound are rejected, so the result carries no modulo bias.
 */
public final class DeterministicRandom {
    private static final long MODULUS = 2_147_483_648L;
    private static final long MULTIPLIER = 1_103_515_245L;
    private static final long INCREMENT = 12_345L;
    private static final long DRAW_LIMIT = 4_294_967_296L;

    private long state;

    public DeterministicRandom(long seed) {
        // PHP's % keeps the dividend's sign; Java's does too.
        state = seed % MODULUS;
    }

    private long bits() {
        state = (MULTIPLIER * state + INCREMENT) % MODULUS;
        return state >> 15;
    }

    public long nextInt(long upper) {
        if (upper <= 1) {
            return 0;
        }
        long bound = DRAW_LIMIT - (DRAW_LIMIT % upper);
        while (true) {
            long draw = bits() * 65_536 + bits();
            if (draw < bound) {
                return draw % upper;
            }
        }
    }

    /** Fisher-Yates from the last index down. O(n). */
    public <T> List<T> shuffled(List<T> values) {
        List<T> out = new ArrayList<>(values);
        for (int index = out.size() - 1; index > 0; index--) {
            Collections.swap(out, index, (int) nextInt(index + 1L));
        }
        return out;
    }
}
