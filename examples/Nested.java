import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.nested.NestedPacker;
import com.packvium.nested.NestedPackingResult;
import com.packvium.nested.PackingLevel;
import com.packvium.result.PackingResult;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.Optional;

/**
 * Nested packing: cartons into a pallet, in one call.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.1.0.jar examples/Nested.java
 * </pre>
 *
 * <p>Real fulfilment is rarely one level. Units go into cartons, cartons go onto a pallet, and
 * sometimes pallets go into a trailer. {@code NestedPacker} runs those levels in order and feeds
 * each level's <em>packed containers</em> into the next level as items -- a carton that came out
 * of level one arrives at level two as a box with its own outer dimensions and its total packed
 * weight.
 *
 * <p>The levels stay independent on purpose. Level two does not reach back and repack level one
 * to get a better pallet, because that would make the carton contents depend on the pallet, and
 * a carton you already taped shut cannot be repacked. If you want that trade-off explored, run
 * the packer yourself with different carton sets and compare.
 */
public final class Nested {
    public static void main(String[] arguments) {
        // What the customer ordered.
        List<Item> items = List.of(
                Item.builder("mug", Dimensions.mm("120", "120", "100"))
                        .weight(Weight.parse("400 g")).quantity(24).build(),
                Item.builder("plate", Dimensions.mm("260", "260", "20"))
                        .weight(Weight.parse("600 g")).quantity(16).build());

        List<PackingLevel> levels = List.of(
                // Level 1: choose cartons. `outerDimensions` matters here -- the next level packs
                // the *outside* of this carton, including its wall thickness.
                new PackingLevel("carton", List.of(
                        Container.builder("box-s", Dimensions.mm("300", "300", "300"))
                                .tareWeight(Weight.parse("300 g"))
                                .maxPayload(Weight.parse("15 kg"))
                                .outerDimensions(Dimensions.mm("310", "310", "310"))
                                .costMinor(120)
                                .build(),
                        Container.builder("box-l", Dimensions.mm("400", "400", "400"))
                                .tareWeight(Weight.parse("500 g"))
                                .maxPayload(Weight.parse("25 kg"))
                                .outerDimensions(Dimensions.mm("412", "412", "412"))
                                .costMinor(180)
                                .build()),
                        // An example must not change answer merely because it is executed under a
                        // profiler or coverage tool. One deterministic start is sufficient to teach
                        // nested packing, and the generous wall-clock value remains only a safety
                        // fuse.
                        Optional.of(PackingConfig.fast(60_000))),

                // Level 2: put those cartons on a pallet. The deck is the inner dimension and the
                // usable stack height is the rest.
                new PackingLevel("pallet", List.of(
                        Container.builder("euro", Dimensions.mm("1200", "800", "1400"))
                                .tareWeight(Weight.parse("25 kg"))
                                .maxPayload(Weight.parse("700 kg"))
                                .costMinor(1500)
                                .build())));

        NestedPackingResult result = new NestedPacker().pack(items, levels);

        // `result.levels()` is one PackingResult per level, in the order you supplied them, so
        // index it back against the level names you chose.
        for (int index = 0; index < result.levels().size(); index++) {
            PackingResult packedLevel = result.levels().get(index);
            System.out.printf("== %s ==\n", levels.get(index).name());
            System.out.printf("   status: %s\n", packedLevel.status().wireName());
            System.out.printf("   containers used: %d\n", packedLevel.containers().size());
            for (PackedContainer packed : packedLevel.containers()) {
                System.out.printf("     %-8s holding %d item(s)\n", packed.container().id(), packed.placements().size());
            }
            if (!packedLevel.unpacked().isEmpty()) {
                System.out.printf("   left over: %d\n", packedLevel.unpacked().size());
            }
            System.out.print("\n");
        }

        int cartons = result.levels().get(0).containers().size();
        int pallets = result.levels().get(result.levels().size() - 1).containers().size();
        System.out.printf("%d carton(s) travelling on %d pallet(s)\n", cartons, pallets);
    }
}
