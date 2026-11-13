import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.RateTable;
import com.packvium.domain.UnratedWeightException;
import com.packvium.result.PackingResult;
import com.packvium.serialization.JsonWriter;
import com.packvium.unit.Weight;
import java.util.List;

/**
 * Objectives: six ways to be "best", and the scenes where they disagree.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.1.0.jar examples/Objectives.java
 * </pre>
 *
 * <p>Every solve returns the arrangement that scores best -- but "best" is a choice, and it is
 * the one setting most likely to make the library look wrong when it is merely answering a
 * different question than you meant to ask. This example builds scenes where two objectives
 * genuinely pick different containers, so the difference is visible rather than asserted.
 *
 * <p>The score is always a lexicographic vector of exact integers, never a float, and its first
 * key is always the unpacked count: no objective will ever leave an item behind to save money.
 * See docs/OBJECTIVE.md for the full key ordering.
 */
public final class Objectives {
    private static final List<Item> WIDGETS = List.of(
            Item.builder("widget", Dimensions.mm("100", "100", "100"))
                    .weight(Weight.parse("500 g")).quantity(8).build());

    public static void main(String[] arguments) {
        // ---------------------------------------------------------------------------------
        // `default` -- fewest containers, then tightest fit. What you want when the boxes are
        // interchangeable and you are simply trying not to open another one.
        // ---------------------------------------------------------------------------------
        Container snug = Container.builder("snug", Dimensions.mm("300", "300", "300"))
                .maxPayload(Weight.parse("20 kg")).costMinor(500).build();
        Container roomy = Container.builder("roomy", Dimensions.mm("400", "400", "400"))
                .maxPayload(Weight.parse("20 kg")).costMinor(150).build();

        System.out.printf("%-18s %s\n", "default", solve(PackingConfig.balanced(), List.of(snug, roomy)));

        // ---------------------------------------------------------------------------------
        // `lowest_cost` -- the cheapest *packaging*. `costMinor` is what the box costs you, so
        // this is the objective for a warehouse buying cartons, not for a shipper paying a
        // carrier.
        // ---------------------------------------------------------------------------------
        System.out.printf("%-18s %s\n", "lowest_cost", solve(objective("lowest_cost").build(), List.of(snug, roomy)));

        // ---------------------------------------------------------------------------------
        // `shipping_cost` -- carrier-billable *weight*: the greater of actual gross weight and
        // dimensional weight. A big light box can bill more than a small heavy one, which is
        // why this is not the same objective as `lowest_cost`.
        //
        // It needs a divisor, and refuses rather than guessing one: a wrong divisor silently
        // misprices every shipment.
        // ---------------------------------------------------------------------------------
        PackingConfig byWeight = carrierBilled("shipping_cost");
        System.out.printf("%-18s %s\n", "shipping_cost", solve(byWeight, List.of(snug, roomy)));

        // ---------------------------------------------------------------------------------
        // `lowest_landed_cost` -- carrier-billable *money*, with the rate card arriving as
        // request data. It exists because weight and money do not always agree: a bracket
        // step, or a minimum charge, can make the cheaper shipment the heavier one.
        //
        // Below the roomy box bills heavier (12,800 g of dimensional weight against the snug
        // box's 5,400) and still costs less, because the snug box's carrier charges a steep
        // first bracket. Rank by weight and you pick the wrong box.
        // ---------------------------------------------------------------------------------
        Container dearPerGram = Container.builder("snug", Dimensions.mm("300", "300", "300"))
                .maxPayload(Weight.parse("20 kg"))
                .rateTable(new RateTable(List.of(6000L, 20000L), List.of(2400L, 3100L))).build();
        Container cheapPerGram = Container.builder("roomy", Dimensions.mm("400", "400", "400"))
                .maxPayload(Weight.parse("20 kg"))
                .rateTable(new RateTable(List.of(6000L, 20000L), List.of(900L, 1500L))).build();
        PackingConfig byMoney = carrierBilled("lowest_landed_cost");
        System.out.printf("%-18s %s\n", "lowest_landed_cost", solve(byMoney, List.of(dearPerGram, cheapPerGram)));

        // A rate card that stops short of the shipment is a refusal, never a silent clamp to
        // the top bracket -- you would otherwise be quoted a price the carrier never published.
        Container tooNarrow = Container.builder("roomy", Dimensions.mm("400", "400", "400"))
                .maxPayload(Weight.parse("20 kg"))
                .rateTable(new RateTable(List.of(2000L), List.of(900L))).build();
        try {
            solve(byMoney, List.of(tooNarrow));
        } catch (UnratedWeightException refusal) {
            System.out.printf("%-18s refused: %s\n", "lowest_landed_cost*", refusal.getMessage());
        }

        // ---------------------------------------------------------------------------------
        // `open_dimension_height` -- pack into the shortest stack, for a lidless container or
        // a pallet that has to clear a doorway.
        // ---------------------------------------------------------------------------------
        System.out.printf("%-18s %s\n", "open_dimension_height",
                solve(objective("open_dimension_height").build(), List.of(snug, roomy)));

        // ---------------------------------------------------------------------------------
        // `maximum_value` -- when not everything fits, leave the *cheap* things behind. Note
        // the honest limitation: this orders by value, it does not solve the knapsack problem
        // to optimality. `quantity(1L)` on the container is what makes it a choice at all --
        // with an unlimited supply the packer simply opens another box.
        // ---------------------------------------------------------------------------------
        List<Container> tiny = List.of(Container.builder("tiny", Dimensions.mm("200", "100", "100"))
                .maxPayload(Weight.parse("20 kg")).quantity(1L).build());
        List<Item> mixed = List.of(
                Item.builder("gold", Dimensions.mm("100", "100", "100"))
                        .weight(Weight.parse("500 g")).quantity(2).value(90000L).build(),
                Item.builder("gravel", Dimensions.mm("100", "100", "100"))
                        .weight(Weight.parse("500 g")).quantity(2).value(10L).build());
        PackingResult result = new Packer(objective("maximum_value").build()).pack(mixed, tiny);
        List<String> kept = result.containers().stream()
                .flatMap(packed -> packed.placements().stream())
                .map(placement -> placement.instance().item().id())
                .sorted().toList();
        List<String> left = result.unpacked().stream()
                .map(unpacked -> unpacked.instance().item().id())
                .sorted().toList();
        System.out.printf("%-18s packed=%s left behind=%s\n", "maximum_value",
                JsonWriter.write(kept), JsonWriter.write(left));
    }

    private static String solve(PackingConfig config, List<Container> containers) {
        PackingResult result = new Packer(config).pack(WIDGETS, containers);
        String chosen = result.containers().isEmpty() ? "none" : result.containers().get(0).container().id();
        return String.format("%-6s score=%s", chosen, JsonWriter.write(result.score()));
    }

    /** Every other setting as {@code new PackingConfig()} leaves it: the balanced profile. */
    private static PackingConfig.Builder objective(String objective) {
        return PackingConfig.builder(SolverProfile.BALANCED).objective(objective);
    }

    /** The common cm/kg carrier convention: 5000 cubic centimetres bill as one kilogram. */
    private static PackingConfig carrierBilled(String objective) {
        return objective(objective)
                .dimensionalWeightDivisor(5000L)
                .dimensionalWeightLengthUnit("cm")
                .dimensionalWeightWeightUnit("kg")
                .build();
    }
}
