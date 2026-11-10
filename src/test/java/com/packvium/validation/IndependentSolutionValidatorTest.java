package com.packvium.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.unit.Rounding;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Expected issue lists were produced by the PHP engine at tag v1.0.0. */
class IndependentSolutionValidatorTest {
    private static final Item A = Item.builder("A", Dimensions.mm(50, 50, 50)).weight(grams(400)).quantity(3)
            .group("g1").tags(List.of("x")).incompatibleTags(List.of("y")).build();
    private static final Item B = Item.builder("B", Dimensions.mm(50, 40, 30)).weight(grams(400))
            .allowedRotations(List.of(Rotation.LWH)).mustBeOnFloor(true)
            .eligibleContainerTags(List.of("cold")).tags(List.of("y")).build();
    private static final Item N1 = Item.builder("N1", Dimensions.mm(20, 20, 20)).quantity(2).group("10").build();
    private static final Item N2 = Item.builder("N2", Dimensions.mm(20, 20, 20)).quantity(2).group("9").build();
    private static final Item Z = Item.builder("Z", Dimensions.mm(10, 10, 10)).build();
    private static final Container C = Container.builder("C", Dimensions.mm(100, 100, 100)).maxPayload(grams(1000))
            .quantity(1L).tags(List.of("dry")).maxItems(2L).build();
    private static final PackingRequest REQUEST = new PackingRequest(List.of(A, B, N1, N2), List.of(C));
    private static final List<PackedContainer> PACKED = List.of(
            new PackedContainer(C, 1, List.of(
                    at(A, 1, 0, 0, 0, Rotation.LWH), at(A, 2, 25, 0, 0, Rotation.LWH),
                    at(B, 1, 50, 50, 10, Rotation.LHW, Dimensions.mm(50, 40, 30)),
                    at(N1, 1, 0, 60, 0, Rotation.LWH), at(N2, 1, 0, 80, 0, Rotation.LWH))),
            new PackedContainer(C, 2, List.of(
                    at(A, 3, 0, 0, 0, Rotation.LWH), at(N1, 2, 90, 0, 0, Rotation.LWH),
                    at(Z, 1, 0, 60, 0, Rotation.LWH), at(A, 1, 0, 0, 50, Rotation.LWH))));
    private static final List<String> PLACEMENT_ISSUES = List.of(
            "collision|A#1 with A#2",
            "incompatible_items|A#1: A is incompatible with B",
            "incompatible_items|A#2: A is incompatible with B",
            "forbidden_rotation|B#1",
            "dimension_mismatch|B#1",
            "must_be_on_floor|B#1: ",
            "container_ineligible|B#1",
            "incompatible_items|B#1: B is incompatible with A");
    private static final List<String> CONTAINER_ISSUES = List.of(
            "payload_exceeded|C#1",
            "max_items_exceeded|C#1",
            "outside_container|N1#2",
            "duplicate_item|A#1",
            "max_items_exceeded|C#2",
            "container_inventory_exceeded|C");

    @Test
    void reportsEveryBrokenGuaranteeInReferenceOrder() {
        ValidationReport report = new IndependentSolutionValidator().validate(REQUEST, PACKED, 0.5, Optional.empty(),
                Optional.of(List.of(new UnpackedItem(new ItemInstance(A, 3), ""))));
        assertEquals(false, report.valid());
        List<String> expected = new java.util.ArrayList<>(PLACEMENT_ISSUES);
        expected.add("insufficient_support|B#1: 0/512000000000 < 0.500000");
        expected.addAll(CONTAINER_ISSUES);
        expected.addAll(List.of(
                "duplicate_item|A#3 is both packed and unpacked",
                "missing_reason|A#3",
                "missing_item|N2#2",
                "unknown_item|Z#1",
                "group_split|10: C#1, C#2",
                "group_split|g1: C#1, C#2",
                "group_partial|9: 1/2 packed"));
        assertEquals(expected, rendered(report));
    }

    @Test
    void skipsCompletenessWithoutAnUnpackedList() {
        ValidationReport report = new IndependentSolutionValidator().validate(REQUEST, PACKED);
        List<String> expected = new java.util.ArrayList<>(PLACEMENT_ISSUES);
        expected.addAll(CONTAINER_ISSUES);
        expected.addAll(List.of("unknown_item|Z#1", "group_split|10: C#1, C#2", "group_split|g1: C#1, C#2"));
        assertEquals(expected, rendered(report));
    }

    @Test
    void acceptsAValidSolution() {
        PackedContainer clean = new PackedContainer(C, 1, List.of(
                at(N1, 1, 0, 0, 0, Rotation.LWH), at(N1, 2, 20, 0, 0, Rotation.LWH)));
        ValidationReport report = new IndependentSolutionValidator().validate(
                new PackingRequest(List.of(N1), List.of(C)), List.of(clean), 0.0, Optional.empty(),
                Optional.of(List.of()));
        assertTrue(report.valid());
        assertEquals(List.of(), report.issues());
    }

    private static List<String> rendered(ValidationReport report) {
        return report.issues().stream().map(issue -> issue.code() + "|" + issue.detail()).toList();
    }

    private static Placement at(Item item, long sequence, long x, long y, long z, Rotation rotation) {
        return at(item, sequence, x, y, z, rotation, item.dimensions().rotated(rotation));
    }

    private static Placement at(Item item, long sequence, long x, long y, long z, Rotation rotation,
            Dimensions dimensions) {
        Point point = new Point(x * 16000, y * 16000, z * 16000);
        return new Placement(new ItemInstance(item, sequence), point, rotation, dimensions, point, dimensions);
    }

    private static Weight grams(long value) {
        return Weight.of(value, "g", Rounding.NEAREST);
    }
}
