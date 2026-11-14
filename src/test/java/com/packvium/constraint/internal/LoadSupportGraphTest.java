package com.packvium.constraint.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.packvium.constraint.LoadUnit;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The cached descending load order; expected values come from the v1.2.0 PHP reference engine. */
class LoadSupportGraphTest {
    private static LoadUnit unit(long x, long y, long z, long l, long w, long h) {
        return new LoadUnit(new AxisAlignedBox(new Point(x, y, z), Dimensions.ofTicks(l, w, h)), 0,
                Optional.empty(), Optional.empty(), "u");
    }

    @Test
    void candidateLoadOrderPreservesHeightTiesAndSiblingIndependence() {
        LoadSupportGraph base = new LoadSupportGraph(List.of(unit(0, 0, 9, 1, 1, 1), unit(10, 0, 0, 1, 1, 10),
                unit(20, 0, 8, 1, 1, 2)));
        assertArrayEquals(new int[] {0, 2, 1}, base.descending());
        LoadSupportGraph above = base.withUnit(unit(30, 0, 10, 1, 1, 1));
        LoadSupportGraph below = base.withUnit(unit(30, 0, 0, 1, 1, 1));
        LoadSupportGraph tied = base.withUnit(unit(30, 0, 8, 1, 1, 2));
        assertArrayEquals(new int[] {3, 0, 2, 1}, above.descending());
        assertArrayEquals(new int[] {0, 2, 1, 3}, below.descending());
        assertArrayEquals(new int[] {0, 2, 3, 1}, tied.descending());
        // Siblings share the parent's order and must never have written into it.
        assertArrayEquals(new int[] {0, 2, 1}, base.descending());
        assertArrayEquals(new int[] {}, new LoadSupportGraph(List.of()).descending());
    }

    @Test
    void anOrderGrownOneUnitAtATimeEqualsAFreshSort() {
        for (long seed = 1; seed <= 25; seed++) {
            SplittableRandom random = new SplittableRandom(seed);
            List<LoadUnit> units = new ArrayList<>();
            LoadSupportGraph grown = new LoadSupportGraph(List.of(), 4);
            for (int index = 0; index < 30; index++) {
                // Few distinct heights, so ties on both z keys are common and the index decides.
                LoadUnit unit = unit(random.nextLong(0, 40), random.nextLong(0, 40), random.nextLong(0, 4),
                        1 + random.nextLong(0, 3), 1 + random.nextLong(0, 3), 1 + random.nextLong(0, 3));
                units.add(unit);
                grown = grown.withUnit(unit, 4);
            }
            int[] expected = IntStream.range(0, units.size()).boxed()
                    .sorted(Comparator.<Integer>comparingLong(index -> -units.get(index).box().z2())
                            .thenComparingLong(index -> -units.get(index).box().origin().z())
                            .thenComparingInt(index -> index))
                    .mapToInt(Integer::intValue).toArray();
            assertArrayEquals(expected, grown.descending(), "seed " + seed);
            assertArrayEquals(expected, new LoadSupportGraph(units).descending(), "seed " + seed);
        }
    }
}
