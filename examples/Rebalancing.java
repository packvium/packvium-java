import com.packvium.Packer;
import com.packvium.algorithm.RebalanceResult;
import com.packvium.algorithm.WeightMove;
import com.packvium.algorithm.WeightRebalancer;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.PackingRequest;
import com.packvium.result.PackingResult;
import com.packvium.support.RationalParser;
import com.packvium.unit.Weight;
import com.packvium.validation.IndependentSolutionValidator;
import com.packvium.validation.ValidationReport;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Even out the weight across pallets after packing.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.6.0.jar examples/Rebalancing.java
 * </pre>
 *
 * <p>The packer opens as few containers as it can and fills the first one tightly. For two pallets
 * on a forklift, that is the wrong shape of answer: one pallet near its weight limit and one nearly
 * empty, when two pallets of similar weight are safer to lift, wrap and stack.
 *
 * <p>{@code WeightRebalancer.rebalance} is a separate, opt-in step you run on a finished packing.
 * It moves one item at a time from the heaviest container to a lighter one, and keeps a move only
 * when the item has a real place in its new container and the whole packing still passes the
 * independent validator. It never adds a container, never drops an item, and stops when no single
 * move narrows the gap (or after {@code maxMoves}, 64 by default). It is greedy, so the result is
 * better, not necessarily the most even split possible.
 */
public final class Rebalancing {
    private static final String RULE = "=".repeat(78);

    private static final List<Item> ITEMS = List.of(
            Item.builder("paint-20l", Dimensions.mm("300", "300", "380")).weight(Weight.parse("24 kg")).quantity(16)
                    .keepUpright(true).build(),
            Item.builder("brush-case", Dimensions.mm("400", "300", "200")).weight(Weight.parse("3 kg")).quantity(10)
                    .build());
    private static final Container PALLET = Container.builder("pallet", Dimensions.mm("1200", "800", "1000"))
            .tareWeight(Weight.parse("25 kg")).maxPayload(Weight.parse("300 kg")).build();

    // Counted work, not a clock, decides the plan (see Reproducibility.java); the time limits here
    // and in `rebalance` below are fuses far above what either step needs.
    private static final PackingConfig CONFIG = PackingConfig.builder(SolverProfile.BALANCED).timeLimitMs(60_000)
            .effortBudget(new EffortBudget(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(8L)))
            .build();

    public static void main(String[] arguments) {
        section("1. As packed: as few pallets as possible, the first one full");
        PackingResult result = new Packer(CONFIG).pack(ITEMS, List.of(PALLET));
        System.out.printf("  %s\n", weights(result.containers()));

        section("2. Rebalanced");
        // The request is what the validator checks the new packing against: every item that was
        // asked for must still be accounted for, exactly once.
        PackingRequest request = new PackingRequest(ITEMS, List.of(PALLET), List.of());
        RebalanceResult rebalanced = WeightRebalancer.rebalance(request, result.containers(), result.unpacked(),
                CONFIG, WeightRebalancer.DEFAULT_MAX_MOVES, 60_000);
        for (WeightMove move : rebalanced.moves()) {
            System.out.printf("  moved %s from %s to %s\n", move.itemId(), move.fromContainerId(),
                    move.toContainerId());
        }
        System.out.printf("  %s\n", weights(rebalanced.containers()));

        ValidationReport report = new IndependentSolutionValidator().validate(request, rebalanced.containers(),
                CONFIG.minimumSupportRatio(), CONFIG.clearance(), result.unpacked());
        System.out.printf("  still a valid packing: %s\n", report.valid());
        System.out.print("""

                  Heavy items move first, because each one narrows the gap most. The paint stays
                  upright in its new place and nothing is left resting on a gap where a moved can
                  used to be -- a move that would do that is rejected, not made.
                """);

        section("3. When there is nothing to improve");
        List<Item> brushes = ITEMS.subList(1, ITEMS.size());
        PackingResult single = new Packer(CONFIG).pack(brushes, List.of(PALLET));
        RebalanceResult unchanged = WeightRebalancer.rebalance(new PackingRequest(brushes, List.of(PALLET), List.of()),
                single.containers(), single.unpacked(), CONFIG, WeightRebalancer.DEFAULT_MAX_MOVES, 60_000);
        System.out.printf("  %s  improved: %s\n", weights(unchanged.containers()), unchanged.improved());
        System.out.print("""

                  One pallet has nothing to balance against, and the result says so rather than
                  inventing a second pallet. The same holds when every candidate move would break a
                  rule: `moves` is empty and the packing comes back as it was.
                """);
    }

    private static String weights(List<PackedContainer> containers) {
        return containers.stream().map(packed -> "%s %s kg (%d items)".formatted(packed.id(),
                RationalParser.decimalString(packed.payloadWeight().ticks(), Weight.TICKS_PER_KG, 1),
                packed.placementCount())).collect(Collectors.joining(", "));
    }

    private static void section(String title) {
        System.out.printf("\n%s\n%s\n%s\n", RULE, title, RULE);
    }
}
