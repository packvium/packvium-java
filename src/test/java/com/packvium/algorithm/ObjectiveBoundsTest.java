package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.packvium.algorithm.ObjectiveBounds.NormalisedContainer;
import com.packvium.algorithm.ObjectiveBounds.NormalisedInstance;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.ShapeType;
import com.packvium.serialization.JsonReader;
import com.packvium.unit.Length;
import com.packvium.unit.Rounding;
import com.packvium.unit.Weight;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Expected vectors and refusals were produced by the PHP engine at tag v1.0.0. */
class ObjectiveBoundsTest {
    private static final Item CUBES = Item.builder("A", Dimensions.mm(50, 50, 50))
            .weight(Weight.of(400, "g", Rounding.NEAREST)).quantity(3).build();
    private static final Item NESTING = Item.builder("N", Dimensions.mm(50, 50, 50))
            .weight(Weight.of(400, "g", Rounding.NEAREST)).quantity(3)
            .nestingHeight(Length.mm(10)).build();
    private static final Container SMALL = Container.builder("C", Dimensions.mm(100, 100, 100))
            .maxPayload(Weight.of(1000, "g", Rounding.NEAREST)).quantity(1L).maxItems(2L)
            .costMinor(5).voidFillReserveRatio(0.1).build();
    private static final Container TALL = Container.builder("D", Dimensions.mm(60, 60, 120)).costMinor(3).build();

    @Test
    void computesTheReferenceVectorsFromARequest() {
        assertArrayEquals(new long[] {1, 1, 5, 750000, 250000}, bounds(List.of(CUBES), List.of(SMALL)));
        assertArrayEquals(new long[] {0, 1, 3, 131944, 312500}, bounds(List.of(CUBES), List.of(SMALL, TALL)));
        assertArrayEquals(new long[] {0, 1, 3, 0, 0}, bounds(List.of(NESTING), List.of(SMALL, TALL)));
        assertArrayEquals(new long[] {0, 1, 3, 0, 0}, bounds(List.of(CUBES, NESTING), List.of(TALL)));
    }

    @Test
    void everyShapeThatShrinksIsFlagged() {
        assertTrue(ObjectiveBounds.occupiesLessThanItsBox(NESTING));
        assertEquals(false, ObjectiveBounds.occupiesLessThanItsBox(CUBES));
        Item compressible = Item.builder("P", Dimensions.mm(10, 10, 10))
                .shapeType(ShapeType.COMPRESSIBLE).compressionRatioPpm(500000L).maxCompressionPressureKpa(10L).build();
        assertTrue(ObjectiveBounds.occupiesLessThanItsBox(compressible));
    }

    @Test
    void refusesASumPastTheDeclaredCeiling() {
        BoundOverflowException refusal = assertThrows(BoundOverflowException.class, () -> ObjectiveBounds.fromNormalised(
                List.of(instance("2000000000000000000000000000000")), List.of(container("1000", Optional.empty(), 1))));
        assertEquals("instance volume sums past the 1000000000000000000000000000000 ceiling the bound path declares;"
                + " refusing rather than returning a number no engine can agree on", refusal.getMessage());
    }

    @Test
    void refusesAResultPastThePortableCeiling() {
        BoundOverflowException refusal = assertThrows(BoundOverflowException.class, () -> ObjectiveBounds.fromNormalised(
                List.of(instance("1")), List.of(container("1000", Optional.of(2L), Long.MAX_VALUE))));
        assertEquals("opening cost bound is 9223372036854775807, above the 9007199254740991 exact portable result"
                + " ceiling", refusal.getMessage());
    }

    /** PHP exhausts memory here building an unbounded cost list; this engine refuses the count instead. */
    @Test
    void refusesASaturatedContainerCount() {
        List<NormalisedInstance> instances = Collections.nCopies(3, instance("900000000000000000000000000"));
        NormalisedContainer unit = new NormalisedContainer(BigInteger.ONE, BigInteger.ONE, 100, 10,
                Optional.empty(), Optional.empty(), Optional.empty(), 1);
        BoundOverflowException refusal = assertThrows(BoundOverflowException.class,
                () -> ObjectiveBounds.fromNormalised(instances, List.of(unit)));
        assertEquals("container count bound is 9223372036854775807, above the 9007199254740991 exact portable"
                + " result ceiling", refusal.getMessage());
    }

    /**
     * The shared corpus Python wrote at the tag. Runs when {@code PACKVIUM_TAG_ROOT} names a
     * checkout of the Packvium suite repository at that tag.
     */
    @Test
    void matchesTheSharedCorpus() throws IOException {
        String root = System.getenv("PACKVIUM_TAG_ROOT");
        assumeTrue(root != null, "PACKVIUM_TAG_ROOT not set");
        Path scene = Path.of(root, "conformance", "scene", "objective-bounds.json");
        assumeTrue(Files.exists(scene), "no objective-bounds scene at the tag");
        Map<?, ?> document = (Map<?, ?>) JsonReader.parse(Files.readString(scene, StandardCharsets.UTF_8));
        int checked = 0;
        for (Object raw : (List<?>) document.get("cases")) {
            Map<?, ?> scenario = (Map<?, ?>) raw;
            List<NormalisedInstance> instances = new ArrayList<>();
            for (Object entry : (List<?>) scenario.get("instances")) {
                Map<?, ?> instance = (Map<?, ?>) entry;
                instances.add(new NormalisedInstance(new BigInteger((String) instance.get("volume")),
                        (Long) instance.get("weight"), (Boolean) instance.get("shrinks")));
            }
            List<NormalisedContainer> containers = new ArrayList<>();
            for (Object entry : (List<?>) scenario.get("containers")) {
                Map<?, ?> container = (Map<?, ?>) entry;
                containers.add(new NormalisedContainer(
                        new BigInteger((String) container.get("usable")),
                        new BigInteger((String) container.get("inner")),
                        (Long) container.get("base_area"),
                        (Long) container.get("height"),
                        optionalLong(container.get("payload")),
                        optionalLong(container.get("max_items")),
                        optionalLong(container.get("quantity")),
                        (Long) container.get("cost_minor")));
            }
            long[] expected = ((List<?>) scenario.get("bounds")).stream().mapToLong(value -> (Long) value).toArray();
            assertArrayEquals(expected, ObjectiveBounds.fromNormalised(instances, containers),
                    String.valueOf(scenario.get("fixture")));
            checked++;
        }
        assertTrue(checked > 0);
    }

    private static long[] bounds(List<Item> items, List<Container> containers) {
        return ObjectiveBounds.compute(new PackingRequest(items, containers).instances(), containers);
    }

    private static NormalisedInstance instance(String volume) {
        return new NormalisedInstance(new BigInteger(volume), 1, false);
    }

    private static NormalisedContainer container(String volume, Optional<Long> quantity, long cost) {
        return new NormalisedContainer(new BigInteger(volume), new BigInteger(volume), 100, 10,
                Optional.empty(), Optional.empty(), quantity, cost);
    }

    private static Optional<Long> optionalLong(Object value) {
        return Optional.ofNullable((Long) value);
    }
}
