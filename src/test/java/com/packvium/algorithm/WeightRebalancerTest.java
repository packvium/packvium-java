package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.RateTable;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.domain.UnratedWeightException;
import com.packvium.objective.UnknownObjectiveException;
import com.packvium.result.PackingResult;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import com.packvium.validation.IndependentSolutionValidator;
import com.packvium.validation.ValidationReport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Weight balancing across already-packed containers, held to Python's
 * {@code test_rebalance.py} and the rebalancing case of {@code test_fixed_placements.py} at tag
 * v1.5.0. Every test is built around the failure mode a naive redistributor has: an item lost or
 * duplicated, a move that strands another item's support, or a spread that does not narrow.
 */
final class WeightRebalancerTest {
    private static final long TICKS_PER_G = Weight.TICKS_PER_G;
    private static final int TRIALS = 20;

    private static Item item(String id, long length, long width, long height, String weight) {
        return Item.builder(id, Dimensions.mm(length, width, height)).weight(Weight.parse(weight)).build();
    }

    private static Container box(String id, long length, long width, long height) {
        return Container.builder(id, Dimensions.mm(length, width, height)).build();
    }

    /** An item resting flat on the container floor, at a point given in ticks. */
    private static Placement floor(Item built, long sequence, long x, long y) {
        Point origin = new Point(x, y, 0);
        return new Placement(new ItemInstance(built, sequence), origin, Rotation.LWH, built.dimensions(), origin,
                built.dimensions(), 1.0);
    }

    /** An item resting exactly on top of {@code below}, fully supported by it alone. */
    private static Placement stacked(Item built, long sequence, Placement below) {
        Point origin = new Point(below.envelopeOrigin().x(), below.envelopeOrigin().y(), below.envelopeBox().z2());
        return new Placement(new ItemInstance(built, sequence), origin, Rotation.LWH, built.dimensions(), origin,
                built.dimensions(), 1.0);
    }

    private static List<Long> weights(List<PackedContainer> containers) {
        return containers.stream().map(container -> container.payloadWeight().ticks()).toList();
    }

    private static long spread(List<Long> weights) {
        return weights.isEmpty() ? 0 : weights.stream().mapToLong(Long::longValue).max().getAsLong()
                - weights.stream().mapToLong(Long::longValue).min().getAsLong();
    }

    private static List<String> moved(RebalanceResult outcome) {
        return outcome.moves().stream().map(WeightMove::itemId).toList();
    }

    /** Every requested instance comes back exactly once, placed or explained. */
    private static void assertAccountingHolds(PackingRequest request, List<PackedContainer> containers,
            List<UnpackedItem> unpacked) {
        List<String> placed = new ArrayList<>();
        containers.forEach(container -> container.expandPlacements()
                .forEach(placement -> placed.add(placement.instance().id())));
        assertEquals(placed.size(), new HashSet<>(placed).size(), "an item instance was packed twice");
        Set<String> found = new HashSet<>(placed);
        unpacked.forEach(item -> found.add(item.instance().id()));
        Set<String> expected = new HashSet<>();
        request.items().forEach(item -> item.instances().forEach(instance -> expected.add(instance.id())));
        assertEquals(expected, found, "items were lost or fabricated");
    }

    private static void assertValid(PackingRequest request, List<PackedContainer> containers,
            List<UnpackedItem> unpacked, PackingConfig config) {
        ValidationReport report = new IndependentSolutionValidator().validate(request, containers,
                config.minimumSupportRatio(), config.clearance(), unpacked);
        assertTrue(report.valid(), report.issues().toString());
    }

    // ------------------------------------------------------------------ narrowing the spread

    /**
     * One container holds 5 kg and 1 kg, the other 1 kg. Moving the 5 kg item only flips which
     * side is heavier, so the 1 kg item moves instead: 5 kg against 2 kg. This is the shared
     * rebalancing case every engine checks.
     */
    @Test
    void aMoveStrictlyReducesThePayloadSpreadBetweenTwoContainers() {
        Layout layout = heavyLightAlone(null);
        assertEquals(5000 * TICKS_PER_G, spread(weights(layout.packed())));
        PackingConfig config = PackingConfig.balanced();
        RebalanceResult outcome = WeightRebalancer.rebalance(layout.request(), layout.packed(), List.of(), config);

        assertEquals(List.of(2000 * TICKS_PER_G, 5000 * TICKS_PER_G),
                weights(outcome.containers()).stream().sorted().toList());
        assertEquals(List.of(new WeightMove("light#1", "box#1", "box#2")), outcome.moves());
        assertEquals(List.of(List.of("heavy#1"), List.of("alone#1", "light#1")), outcome.containers().stream()
                .map(container -> container.placements().stream().map(p -> p.instance().id()).toList()).toList());
        assertTrue(outcome.improved());
        assertAccountingHolds(layout.request(), outcome.containers(), List.of());
        assertValid(layout.request(), outcome.containers(), List.of(), config);
    }

    /** Moving the base would leave the item on it floating; the top item moves instead. */
    @Test
    void aMoveThatWouldStrandASupportedItemIsDeclined() {
        Item base = item("base", 40, 40, 40, "5000 g");
        Item top = Item.builder("top", Dimensions.mm(40, 40, 40)).weight(Weight.parse("1000 g"))
                .minimumSupportRatio(1.0).build();
        Container type = box("box", 200, 200, 200);
        PackingRequest request = new PackingRequest(List.of(base, top), List.of(type), List.of());
        Placement basePlacement = floor(base, 1, 0, 0);
        List<PackedContainer> packed = List.of(
                new PackedContainer(type, 1, List.of(basePlacement, stacked(top, 1, basePlacement))),
                new PackedContainer(type, 2, List.of()));
        assertEquals(List.of(6000 * TICKS_PER_G, 0L), weights(packed));

        PackingConfig config = PackingConfig.balanced();
        RebalanceResult outcome = WeightRebalancer.rebalance(request, packed, List.of(), config);

        PackedContainer baseContainer = outcome.containers().stream()
                .filter(container -> container.id().equals("box#1")).findFirst().orElseThrow();
        assertEquals(List.of("base#1"), baseContainer.placements().stream().map(p -> p.instance().id()).toList());
        assertEquals(List.of(1000 * TICKS_PER_G, 5000 * TICKS_PER_G),
                weights(outcome.containers()).stream().sorted().toList());
        assertEquals(List.of("top#1"), moved(outcome));
        assertAccountingHolds(request, outcome.containers(), List.of());
        assertValid(request, outcome.containers(), List.of(), config);
    }

    @Test
    void aSingleContainerHasNothingToRebalance() {
        Item solo = item("solo", 40, 40, 40, "500 g");
        Container type = box("box", 200, 200, 200);
        PackingRequest request = new PackingRequest(List.of(solo), List.of(type), List.of());
        List<PackedContainer> packed = List.of(new PackedContainer(type, 1, List.of(floor(solo, 1, 0, 0))));

        RebalanceResult outcome = WeightRebalancer.rebalance(request, packed);

        assertEquals(List.of(), outcome.moves());
        assertFalse(outcome.improved());
        assertEquals(packed, outcome.containers());
    }

    @Test
    void anAlreadyBalancedPairIsLeftUntouched() {
        Item a = item("a", 40, 40, 40, "1000 g");
        Item b = item("b", 40, 40, 40, "1000 g");
        Container type = box("box", 200, 200, 200);
        PackingRequest request = new PackingRequest(List.of(a, b), List.of(type), List.of());
        List<PackedContainer> packed = List.of(new PackedContainer(type, 1, List.of(floor(a, 1, 0, 0))),
                new PackedContainer(type, 2, List.of(floor(b, 1, 0, 0))));

        RebalanceResult outcome = WeightRebalancer.rebalance(request, packed);

        assertEquals(List.of(), outcome.moves());
        assertEquals(packed, outcome.containers());
    }

    // --------------------------------------------------------------------- randomised property

    /**
     * A plausible order over one container type with a payload ceiling low enough that a real
     * solve splits it. Seeded, so every run draws the same orders.
     */
    private static Order generate(int seed) {
        Random random = new Random(seed);
        List<Item> items = new ArrayList<>();
        int count = 2 + random.nextInt(5);
        for (int index = 0; index < count; index++) {
            items.add(Item.builder("i" + index, Dimensions.mm(side(random), side(random), side(random)))
                    .quantity(1 + random.nextInt(3))
                    .weight(Weight.parse((100 + random.nextInt(800)) + " g")).build());
        }
        Container type = Container.builder("c0", Dimensions.mm(150, 150, 150))
                .quantity((long) (2 + random.nextInt(3)))
                .maxPayload(Weight.parse((1 + random.nextInt(2)) + " kg")).build();
        return new Order(items, List.of(type));
    }

    private static long side(Random random) {
        return 10 + 5L * random.nextInt(6);
    }

    static IntStream seeds() {
        return IntStream.range(0, TRIALS);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void rebalanceNeverLosesOrDuplicatesAnItem(int seed) {
        Order order = generate(seed);
        PackingRequest request = new PackingRequest(order.items(), order.containers(), List.of());
        PackingResult result = new Packer(PackingConfig.balanced(250)).pack(order.items(), order.containers());

        PackingConfig config = PackingConfig.balanced(200);
        RebalanceResult outcome = WeightRebalancer.rebalance(request, result.containers(), result.unpacked(), config);

        assertAccountingHolds(request, outcome.containers(), result.unpacked());
        assertValid(request, outcome.containers(), result.unpacked(), config);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void rebalanceNeverWidensThePayloadSpread(int seed) {
        Order order = generate(seed);
        PackingRequest request = new PackingRequest(order.items(), order.containers(), List.of());
        PackingResult result = new Packer(PackingConfig.balanced(250)).pack(order.items(), order.containers());
        List<Long> before = weights(result.containers());

        RebalanceResult outcome = WeightRebalancer.rebalance(request, result.containers(), result.unpacked(),
                PackingConfig.balanced(200));

        List<Long> after = weights(outcome.containers());
        assertTrue(spread(after) <= spread(before));
        if (outcome.improved()) {
            assertNotEquals(before, after);
        }
    }

    // ------------------------------------------------------------------------- landed cost

    private static PackingConfig landedCost() {
        return PackingConfig.builder(SolverProfile.BALANCED).objective("lowest_landed_cost")
                .dimensionalWeightDivisor(5_000L).dimensionalWeightLengthUnit("cm").dimensionalWeightWeightUnit("kg")
                .build();
    }

    private static Item bricks() {
        return Item.builder("brick", Dimensions.mm(100, 100, 100)).weight(Weight.parse("1000g")).quantity(4).build();
    }

    private static Container rated(String id, long length, long bracketG, long priceMinor) {
        return Container.builder(id, Dimensions.mm(length, 100, 100))
                .rateTable(new RateTable(List.of(bracketG), List.of(priceMinor))).build();
    }

    /** The only spread-improving move bills the lighter box past its card, so it is not made. */
    @Test
    void aRebalanceMoveNeverPricesAContainerPastItsBracket() {
        Item bricks = bricks();
        Container wide = rated("wide", 300, 4_000, 500);
        Container narrow = rated("narrow", 400, 1_500, 300);
        PackingConfig config = landedCost();
        PackingResult result = new Packer(config).pack(List.of(bricks), List.of(wide, narrow));
        assertEquals(List.of("wide", "narrow"),
                result.containers().stream().map(container -> container.container().id()).toList());
        PackingRequest request = new PackingRequest(List.of(bricks), List.of(wide, narrow), List.of());

        RebalanceResult rebalanced = WeightRebalancer.rebalance(request, result.containers(), result.unpacked(),
                config);
        assertEquals(List.of(), rebalanced.moves());
        assertEquals(List.of("wide", "narrow"),
                rebalanced.containers().stream().map(container -> container.container().id()).toList());
        // The veto is objective-gated: under a plain configuration the same packing moves a brick.
        RebalanceResult plain = WeightRebalancer.rebalance(request, result.containers(), result.unpacked(),
                PackingConfig.balanced());
        assertEquals(1, plain.moves().size());
    }

    @Test
    void rebalanceRefusesAnUnpriceableInput() {
        Item bricks = bricks();
        Container narrow = rated("narrow", 400, 1_500, 300);
        PackingConfig config = landedCost();
        PackingResult result = new Packer(config).pack(List.of(bricks), List.of(rated("wide", 300, 4_000, 500),
                narrow));
        // The wide card shrinks after the fact, so the input itself is unpriceable.
        Container stingy = rated("wide", 300, 1_000, 500);
        List<PackedContainer> reshaped = result.containers().stream()
                .map(container -> new PackedContainer(container.container().id().equals("wide")
                        ? stingy : container.container(), container.sequence(), container.placements()))
                .toList();
        PackingRequest request = new PackingRequest(List.of(bricks), List.of(stingy, narrow), List.of());

        UnratedWeightException refusal = assertThrows(UnratedWeightException.class,
                () -> WeightRebalancer.rebalance(request, reshaped, result.unpacked(), config));
        assertTrue(refusal.getMessage().contains("no published price"), refusal.getMessage());
    }

    /** Pricing needs a divisor and a card on every available container, used or not. */
    @Test
    void rebalanceAppliesTheSameLandedCostAdmissionAsPack() {
        Item parcel = Item.builder("parcel", Dimensions.mm(100, 100, 100)).weight(Weight.parse("500g")).build();
        Container card = Container.builder("rated", Dimensions.mm(200, 200, 200))
                .rateTable(new RateTable(List.of(2_000L), List.of(500L))).build();
        PackingConfig valid = PackingConfig.builder(SolverProfile.BALANCED)
                .objective("lowest_landed_cost").dimensionalWeightDivisor(8_000L).dimensionalWeightLengthUnit("cm")
                .dimensionalWeightWeightUnit("kg").build();
        PackingResult result = new Packer(valid).pack(List.of(parcel), List.of(card));

        PackingConfig missingDivisor = PackingConfig.builder(SolverProfile.BALANCED)
                .objective("lowest_landed_cost").build();
        PackingRequest request = new PackingRequest(List.of(parcel), List.of(card), List.of());
        UnknownObjectiveException divisor = assertThrows(UnknownObjectiveException.class,
                () -> WeightRebalancer.rebalance(request, result.containers(), result.unpacked(), missingDivisor));
        assertTrue(divisor.getMessage().contains("dimensional_weight_divisor"));

        PackingRequest withUnused = new PackingRequest(List.of(parcel),
                List.of(card, box("untabled", 300, 300, 300)), List.of());
        UnknownObjectiveException table = assertThrows(UnknownObjectiveException.class,
                () -> WeightRebalancer.rebalance(withUnused, result.containers(), result.unpacked(), valid));
        assertTrue(table.getMessage().contains("rate_table on every container; 'untabled'"));
    }

    // -------------------------------------------------------------- moves declined or capped

    /** The 6 kg / 1 kg layout, optionally with the lone item in a container type of its own. */
    private static Layout heavyLightAlone(Container aloneBox) {
        Item heavy = item("heavy", 40, 40, 40, "5000 g");
        Item light = item("light", 40, 40, 40, "1000 g");
        Item alone = item("alone", 40, 40, 40, "1000 g");
        Container type = box("box", 200, 200, 200);
        Container other = aloneBox == null ? type : aloneBox;
        List<Container> types = other == type ? List.of(type) : List.of(type, other);
        PackingRequest request = new PackingRequest(List.of(heavy, light, alone), types, List.of());
        List<PackedContainer> packed = List.of(
                new PackedContainer(type, 1, List.of(floor(heavy, 1, 0, 0), floor(light, 1, 50, 0))),
                new PackedContainer(other, other == type ? 2 : 1, List.of(floor(alone, 1, 0, 0))));
        return new Layout(request, packed);
    }

    @Test
    void theMoveBudgetCapsHowManyMovesAreCommitted() {
        Layout layout = heavyLightAlone(null);
        RebalanceResult outcome = WeightRebalancer.rebalance(layout.request(), layout.packed(), List.of(),
                PackingConfig.balanced(), 1, WeightRebalancer.DEFAULT_TIME_LIMIT_MS);
        assertEquals(List.of("light#1"), moved(outcome));
    }

    @Test
    void aDestinationWithNoRoomLeftReceivesNothing() {
        Layout layout = heavyLightAlone(box("snug", 40, 40, 40));
        RebalanceResult outcome = WeightRebalancer.rebalance(layout.request(), layout.packed());
        assertEquals(List.of(), outcome.moves());
        assertEquals(layout.packed(), outcome.containers());
    }

    @Test
    void anItemMovedOntoAFullFloorIsStackedAndStaysSupported() {
        Layout layout = heavyLightAlone(box("tower", 40, 40, 80));
        PackingConfig config = PackingConfig.balanced();
        RebalanceResult outcome = WeightRebalancer.rebalance(layout.request(), layout.packed(), List.of(), config);

        assertEquals(List.of("light#1"), moved(outcome));
        PackedContainer tower = outcome.containers().stream().filter(c -> c.id().equals("tower#1")).findFirst()
                .orElseThrow();
        Placement landed = tower.placements().stream().filter(p -> p.instance().id().equals("light#1")).findFirst()
                .orElseThrow();
        assertEquals(Length.mm(40).ticks(), landed.position().z());
        assertEquals(1.0, landed.supportRatio());
        assertAccountingHolds(layout.request(), outcome.containers(), List.of());
        assertValid(layout.request(), outcome.containers(), List.of(), config);
    }

    @Test
    void aWeightlessItemIsNeverMovedSinceItCannotChangeTheSpread() {
        Item heavy = item("heavy", 40, 40, 40, "5000 g");
        Item feather = Item.builder("feather", Dimensions.mm(40, 40, 40)).build();
        Item alone = item("alone", 40, 40, 40, "1000 g");
        Container type = box("box", 200, 200, 200);
        PackingRequest request = new PackingRequest(List.of(heavy, feather, alone), List.of(type), List.of());
        List<PackedContainer> packed = List.of(
                new PackedContainer(type, 1, List.of(floor(heavy, 1, 0, 0), floor(feather, 1, 50, 0))),
                new PackedContainer(type, 2, List.of(floor(alone, 1, 0, 0))));
        assertEquals(List.of(), WeightRebalancer.rebalance(request, packed).moves());
    }

    @Test
    void rebalancingNeverMovesAFixedItem() {
        List<Item> items = List.of(Item.builder("cube", Dimensions.mm(100, 100, 100)).weight(Weight.parse("5000g"))
                .quantity(5).build());
        List<Container> containers = List.of(Container.builder("box", Dimensions.mm(200, 100, 200))
                .quantity(2L).build());
        List<FixedPlacement> fixed = List.of(at(0, 0, 0, 1), at(100, 0, 0, 1), at(0, 0, 100, 1), at(0, 0, 0, 2));
        PackingConfig config = PackingConfig.balanced();
        PackingResult packed = new Packer(config).pack(items, containers, fixed);
        PackingRequest model = new PackingRequest(items, containers, fixed);

        RebalanceResult rebalanced = WeightRebalancer.rebalance(model, packed.containers(), packed.unpacked(), config);

        assertEquals(2, packed.containers().size());
        assertTrue(moved(rebalanced).stream().noneMatch(Set.of("cube#1", "cube#2", "cube#3", "cube#4")::contains));
        ValidationReport report = new IndependentSolutionValidator().validate(model, rebalanced.containers(), 0.0,
                Length.ZERO, packed.unpacked());
        assertTrue(report.valid(), report.issues().toString());
    }

    private static FixedPlacement at(long xMm, long yMm, long zMm, long instance) {
        return new FixedPlacement("cube", "box", new Point(Length.mm(xMm).ticks(), Length.mm(yMm).ticks(),
                Length.mm(zMm).ticks()), Rotation.LWH, instance);
    }

    private record Layout(PackingRequest request, List<PackedContainer> packed) {
    }

    private record Order(List<Item> items, List<Container> containers) {
    }
}
