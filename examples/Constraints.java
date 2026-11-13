import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.explain.Explain;
import com.packvium.result.PackingResult;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Constraints: how to say "this may not go there" and get told why.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.1.0.jar examples/Constraints.java
 * </pre>
 *
 * <p>The solver's job is not only to fit boxes. Most real packing rules are refusals -- this
 * side up, nothing on top of that, keep the chemicals away from the food -- and the useful part
 * of the answer is often the item that did <em>not</em> fit and the reason it did not.
 *
 * <p>Every constraint here is a setter on {@code Item.builder} or {@code Container.builder}.
 * None of them needs a custom class, and none changes how you call {@code pack}.
 */
public final class Constraints {
    // The six axis-aligned orientations, which is also the default. Narrow this list when an
    // item may only be set down certain ways -- `List.of(Rotation.LWH, Rotation.WLH)` keeps a
    // carton flat while still letting it turn on the spot.
    private static final List<Rotation> ANY_ORIENTATION = List.of(
            Rotation.LWH, Rotation.LHW, Rotation.WLH,
            Rotation.WHL, Rotation.HLW, Rotation.HWL);

    public static void main(String[] arguments) {
        List<Item> items = List.of(
                // `keepUpright` forbids every rotation that would tip the item over. An open tub
                // of paint is the usual reason.
                item("paint", Dimensions.mm("200", "200", "250"), "5 kg").quantity(2).keepUpright(true).build(),

                // `mustBeOnFloor` keeps the item on the container floor, and `maxTopLoad` caps
                // what may rest directly on it. Note "directly": this is not a whole-stack limit.
                item("glass-panel", Dimensions.mm("400", "300", "40"), "8 kg")
                        .mustBeOnFloor(true)
                        .maxTopLoad(Weight.parse("2 kg"))
                        .build(),

                // `stackable(false)` means nothing may be placed on this item at all.
                item("cake", Dimensions.mm("250", "250", "150"), "1 kg").stackable(false).build(),

                // Tags are how two items refuse each other. `incompatibleTags` is checked both
                // ways, so tagging one side is enough.
                item("bleach", Dimensions.mm("120", "120", "300"), "2 kg")
                        .quantity(2)
                        .tags(List.of("hazmat"))
                        .incompatibleTags(List.of("food"))
                        .build(),
                item("flour", Dimensions.mm("200", "150", "100"), "1500 g").quantity(3).tags(List.of("food")).build(),

                // Longer than the crate's longest inner edge in every orientation, so no solver
                // can place it. It is here to show what a refusal looks like.
                Item.builder("ladder", Dimensions.mm("1800", "300", "100")).weight(Weight.parse("6 kg")).build());

        List<Container> containers = List.of(
                // `maxPayload` is the weight the container may carry, excluding its own tare.
                Container.builder("crate", Dimensions.mm("600", "500", "500"))
                        .tareWeight(Weight.parse("3 kg"))
                        .maxPayload(Weight.parse("25 kg"))
                        .costMinor(400)
                        .build());

        PackingResult result = new Packer(PackingConfig.balanced()).pack(items, containers);

        System.out.printf("status: %s\n", result.status().wireName());
        System.out.printf("containers opened: %d\n", result.containers().size());

        for (int index = 0; index < result.containers().size(); index++) {
            PackedContainer packed = result.containers().get(index);
            System.out.printf("\ncrate #%d (%s): %d placement(s)\n",
                    index + 1, packed.container().id(), packed.placements().size());
            for (Placement placement : packed.placements()) {
                System.out.printf("  %-12s at (%s, %s, %s) mm\n",
                        placement.instance().item().id(),
                        millimetres(placement.position().x()),
                        millimetres(placement.position().y()),
                        millimetres(placement.position().z()));
            }
        }

        // Two crates for a load that would fit in one by volume: `bleach` is tagged `hazmat` and
        // refuses `food`, so it cannot share a container with `flour`. Nothing asked the solver
        // to open a second crate -- the constraint did.

        // The refusals are the interesting half. `Explain.unpackedItem` turns the structured
        // reason into a sentence, so you can show a human why their order will not ship as one
        // box without teaching them the reason codes.
        if (!result.unpacked().isEmpty()) {
            System.out.print("\nnot packed:\n");
            for (UnpackedItem unpacked : result.unpacked()) {
                System.out.printf("  %-12s %s\n", unpacked.instance().item().id(), Explain.unpackedItem(unpacked));
            }
        }

        // ----------------------------------------------------------- one rule at a time
        //
        // The four rules below are each shown twice: the same items, the same container, once
        // without the rule and once with it. A constraint you cannot watch change the answer is
        // one the reader has to take on faith, and the pair makes the rule -- rather than the
        // geometry -- provably the reason.
        //
        // Note what "the rule bit" looks like. Only sometimes is it a refusal; more often the
        // solver satisfies the rule by opening another container, which costs money and is the
        // answer you actually wanted to see coming. So both numbers are printed.

        List<Container> shelf = List.of(container("shelf", Dimensions.mm("800", "400", "500")));

        // `allowedRotations` narrows the six orientations to the ones you permit, and
        // `List.of(Rotation.LWH, Rotation.WLH)` is the pair that keeps the item's own height
        // vertical -- what you want for anything with a printed face or an open top. The pole
        // is 700 mm tall and the shelf is 500 mm deep, so it fits only by being laid down,
        // which is what this forbids.
        Dimensions pole = Dimensions.mm("90", "90", "700");
        compare(
                "allowedRotations -- a pole that only fits lying down, forbidden from lying down",
                List.of(item("pole", pole, "1 kg").build()),
                List.of(item("pole", pole, "1 kg").allowedRotations(List.of(Rotation.LWH, Rotation.WLH)).build()),
                shelf);

        // `maxStackedItems` caps how many units may sit above one item -- a pallet-pattern rule
        // ("three high, no more"), not a weight limit. The column below is one tin wide, so
        // height is the only way to fit more, and the second container is the price of the cap.
        List<Container> column = List.of(container("column", Dimensions.mm("160", "160", "600")));
        Dimensions tin = Dimensions.mm("150", "150", "120");
        compare(
                "maxStackedItems -- five tins fit in one column; three-high needs two columns",
                List.of(item("tin", tin, "800 g").quantity(5).build()),
                List.of(item("tin", tin, "800 g").quantity(5).maxStackedItems(3L).build()),
                column);

        // `minimumSupportRatio` is how much of an item's base must rest on something solid. The
        // plinth stands on the floor and covers a quarter of the ledge, and the ledge is too
        // shallow for the slab to stand on edge -- so the only place the slab fits is perched
        // on the plinth, on a quarter of its base. At 0.9 that is refused and a second ledge
        // opens.
        List<Container> ledge = List.of(container("ledge", Dimensions.mm("400", "400", "350")));
        Item plinth = item("plinth", Dimensions.mm("200", "200", "300"), "5 kg").mustBeOnFloor(true).build();
        Dimensions slab = Dimensions.mm("400", "400", "60");
        compare(
                "minimumSupportRatio -- a slab perched on a quarter of its base",
                List.of(plinth, item("slab", slab, "9 kg").build()),
                List.of(plinth, item("slab", slab, "9 kg").minimumSupportRatio(0.9).build()),
                ledge);

        // `group` is atomic: every member ships in one container or none of them does. The
        // third part is deliberately too long for the shelf, so it takes the other two down
        // with it rather than shipping two thirds of an assembly nobody can use.
        List<Dimensions> parts = List.of(
                Dimensions.mm("200", "200", "100"),
                Dimensions.mm("200", "200", "100"),
                Dimensions.mm("900", "100", "100"));
        List<Item> loose = new ArrayList<>();
        List<Item> grouped = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            String id = "kit-" + (index + 1);
            loose.add(item(id, parts.get(index), "2 kg").build());
            grouped.add(item(id, parts.get(index), "2 kg").group("assembly").build());
        }
        compare("group -- one member cannot be placed, so none of them is", loose, grouped, shelf);

        // Every reason code above is a fact about the request, not a solver failure -- which is
        // why `Explain.unpackedItem` can turn it into a sentence a customer is allowed to read.
    }

    private static Item.Builder item(String id, Dimensions dimensions, String weight) {
        return Item.builder(id, dimensions).weight(Weight.parse(weight)).allowedRotations(ANY_ORIENTATION);
    }

    private static Container container(String id, Dimensions inner) {
        return Container.builder(id, inner).maxPayload(Weight.parse("40 kg")).build();
    }

    private static void compare(String rule, List<Item> without, List<Item> withRule, List<Container> containers) {
        System.out.printf("\n%s\n", rule);
        report("without the rule", without, containers);
        report("with the rule   ", withRule, containers);
    }

    private static void report(String label, List<Item> items, List<Container> containers) {
        PackingResult outcome = new Packer(PackingConfig.balanced()).pack(items, containers);
        System.out.printf("  %s: %d container(s), %d placed, %d refused\n",
                label, outcome.containers().size(), outcome.packedItemCount(), outcome.unpacked().size());
        for (UnpackedItem unpacked : outcome.unpacked()) {
            System.out.printf("      %s\n", Explain.unpackedItem(unpacked));
        }
    }

    /** Positions are exact integers in 1/16000 mm; render them for a human, to two places. */
    private static String millimetres(long ticks) {
        BigDecimal value = BigDecimal.valueOf(ticks)
                .divide(BigDecimal.valueOf(Length.TICKS_PER_MM))
                .setScale(2, RoundingMode.HALF_UP);
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
