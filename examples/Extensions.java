import com.packvium.Packer;
import com.packvium.algorithm.RawSolution;
import com.packvium.algorithm.SingleContainerSolution;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.Rotation;
import com.packvium.extension.ContainerSelector;
import com.packvium.extension.DefaultContainerSelector;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.objective.DefaultSolutionScorer;
import com.packvium.objective.ObjectiveScore;
import com.packvium.objective.SolutionScorer;
import com.packvium.result.PackingResult;
import com.packvium.serialization.JsonWriter;
import com.packvium.support.SortKey;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extension points: rules the request has no field for.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.6.0.jar examples/Extensions.java
 * </pre>
 *
 * <p>Most packing rules are already fields on {@code Item} and {@code Container} -- see
 * Constraints.java. This example is about the rules that are not, and it comes with an honest
 * warning about what you give up by writing one.
 *
 * <p><b>An extension point is an in-process, one-language interface.</b> A custom constraint or
 * scorer has no representation in the JSON request, so an engine driven over JSON cannot see it,
 * and nothing can check that the other engines agree about it. Use one to specialise a single Java
 * application. A rule that must hold for every caller of every engine belongs in the request as
 * data -- a {@code policy} rule, a tag, a field.
 *
 * <p>Three extension points are shown, each registered without touching the solver:
 *
 * <ul>
 *   <li>a {@code PlacementConstraint}, through {@code ExtensionRegistry}, refuses candidate
 *       positions;
 *   <li>a {@code SolutionScorer}, passed to {@code Packer}, ranks complete solutions;
 *   <li>a {@code ContainerSelector}, through {@code ExtensionRegistry}, decides which container
 *       type a multi-container search opens next.
 * </ul>
 *
 * <p>{@code SolutionScorer} and {@code ContainerSelector} are handed the solver's own results,
 * {@code RawSolution} and {@code SingleContainerSolution}, which live in the internal
 * {@code com.packvium.algorithm} package. Implementing either interface means naming them, and
 * their names are held by the interfaces' frozen signatures; read only what is shown here from
 * them -- a {@code RawSolution}'s {@code containers} and {@code unpacked} -- since the rest is not
 * frozen.
 */
public final class Extensions {
    /**
     * Counted work, not a clock, bounds every solve here, so the example prints the same on every
     * machine. The time limit is only a fuse, far above what these solves need.
     */
    private static PackingConfig config() {
        return PackingConfig.builder(SolverProfile.BALANCED).timeLimitMs(60_000)
                .effortBudget(new EffortBudget(Optional.empty(), Optional.empty(), Optional.of(20_000L),
                        Optional.empty()))
                .build();
    }

    // -----------------------------------------------------------------------------------------
    // A custom placement constraint. `maxTopLoad` caps what may rest on an item and
    // `mustBeOnFloor` pins one to the bottom -- but neither says "nothing fragile above waist
    // height, because that is where it gets knocked off a trolley". That is a real warehouse rule
    // with no field, so it is a constraint.
    //
    // A constraint answers about *one candidate position*. It never searches; it is asked many
    // thousands of times per solve, so keep it O(1) in the number of placements where you can.
    // This one is: it looks at the candidate's z coordinate and nothing else.
    // -----------------------------------------------------------------------------------------
    private record FragileHeightLimit(Length limit, String tag) implements PlacementConstraint {
        @Override
        public ConstraintResult evaluate(ConstraintContext context) {
            if (!context.item().item().tags().contains(tag)) {
                return ConstraintResult.allow();
            }
            // `Point` carries raw tick counts, not `Length` objects: it is built once per
            // candidate and this is the hot path.
            if (context.point().z() <= limit.ticks()) {
                return ConstraintResult.allow();
            }
            // The code is yours. It travels into the unpacked reason, so make it something a
            // person reading a failed order will understand.
            return ConstraintResult.reject("fragile_too_high", "base at %smm is above the %smm limit for %s items"
                    .formatted(new Length(context.point().z()).decimal("mm"), limit.decimal("mm"), tag));
        }
    }

    // -----------------------------------------------------------------------------------------
    // A custom solution scorer. The six built-in objectives rank by space, packaging cost, billed
    // weight, landed money, stack height or value. None of them cares whether the weight is spread
    // *evenly across the containers* -- which is what a two-person lift or a van's balance depends
    // on, and is nowhere in the request.
    //
    // Return exact integers, lower is better, compared left to right. Keep the unpacked count
    // first unless you really mean "leave items behind to score better", and fall back to the
    // default vector for everything your rule does not care about -- otherwise two equally
    // balanced solutions are ranked by luck.
    // -----------------------------------------------------------------------------------------
    private static final class EvenlyLoadedContainers implements SolutionScorer {
        @Override
        public ObjectiveScore score(RawSolution solution) {
            List<Long> loads = solution.containers().stream().map(c -> c.payloadWeight().ticks()).toList();
            long spread = loads.isEmpty() ? 0 : loads.stream().max(Long::compare).get()
                    - loads.stream().min(Long::compare).get();
            List<Long> fallback = new DefaultSolutionScorer().score(solution).components();
            List<Long> components = new ArrayList<>(List.of((long) solution.unpacked().size(), spread));
            components.addAll(fallback.subList(1, fallback.size()));
            return new ObjectiveScore(components);
        }
    }

    // -----------------------------------------------------------------------------------------
    // A custom container selector. When several container types are offered, the search asks the
    // selector which one to open next, comparing each type's best single-container fill. The
    // default opens whichever holds the most items, then the cheapest. This one keeps "most
    // items" first -- drop it and the search happily opens box after box that each hold one item
    // -- and then prefers the *smallest* box, however much it costs, because shipping air costs
    // more than cardboard.
    // -----------------------------------------------------------------------------------------
    private static final class SmallestBoxFirst implements ContainerSelector {
        @Override
        public SortKey score(Container container, SingleContainerSolution solution) {
            SortKey fallback = new DefaultContainerSelector().score(container, solution);
            // Whole cubic millimetres are plenty to rank boxes by and stay inside a long; the
            // exact volume in ticks does not.
            Dimensions size = container.innerDimensions();
            long cubicMm = size.length().ticks() / Length.TICKS_PER_MM * (size.width().ticks() / Length.TICKS_PER_MM)
                    * (size.height().ticks() / Length.TICKS_PER_MM);
            return SortKey.of(fallback.elements().get(0), cubicMm, container.id());
        }
    }

    public static void main(String[] arguments) {
        // The footprint is only as wide as one crate, so the column has to grow upwards and the
        // rule has something to refuse. A rule that never fires teaches nothing.
        List<Item> items = List.of(
                Item.builder("crate", Dimensions.mm("400", "400", "300")).weight(Weight.parse("12 kg")).quantity(3)
                        .build(),
                Item.builder("vase", Dimensions.mm("400", "400", "200")).weight(Weight.parse("2 kg")).quantity(2)
                        .allowedRotations(Rotation.all()).tags(List.of("fragile")).build());
        List<Container> column = List.of(Container.builder("column", Dimensions.mm("400", "400", "1300"))
                .maxPayload(Weight.parse("200 kg")).quantity(1L).build());

        PackingResult unrestricted = new Packer(config()).pack(items, column);
        List<String> heights = vaseHeights(unrestricted.containers());
        System.out.print("without the rule, the highest vase sits at " + heights.get(heights.size() - 1) + " mm\n");

        ExtensionRegistry rule = ExtensionRegistry.withConstraints(
                List.of(new FragileHeightLimit(Length.mm("400"), "fragile")));
        PackingResult restricted = new Packer(config(), rule).pack(items, column);
        System.out.printf("with a 400mm limit, vases sit at %s and %d were left behind\n",
                JsonWriter.write(vaseHeights(restricted.containers())), restricted.unpacked().size());

        // Two heavy items and two light ones, two boxes, two slots each. Every arrangement uses
        // the same volume, so the built-in objectives are indifferent -- and put both anvils in
        // one box.
        List<Item> lopsided = List.of(
                Item.builder("anvil", Dimensions.mm("200", "200", "200")).weight(Weight.parse("10 kg")).quantity(2)
                        .build(),
                Item.builder("pillow", Dimensions.mm("200", "200", "200")).weight(Weight.parse("1 kg")).quantity(2)
                        .build());
        List<Container> twoBoxes = List.of(Container.builder("box", Dimensions.mm("400", "200", "200"))
                .maxPayload(Weight.parse("50 kg")).quantity(2L).build());

        System.out.print("\n");
        Map<String, Optional<SolutionScorer>> scorers = new LinkedHashMap<>();
        scorers.put("default", Optional.empty());
        scorers.put("evenly loaded", Optional.of(new EvenlyLoadedContainers()));
        scorers.forEach((label, scorer) -> {
            PackingResult result = new Packer(config(), ExtensionRegistry.none(), scorer, System::nanoTime)
                    .pack(lopsided, twoBoxes);
            List<List<String>> contents = new ArrayList<>();
            List<String> weights = new ArrayList<>();
            for (PackedContainer container : result.containers()) {
                contents.add(container.placements().stream().map(placement -> placement.instance().item().id())
                        .sorted().toList());
                weights.add(container.payloadWeight().decimal("kg") + " kg");
            }
            System.out.printf("%15s: %s  ->  %s\n", label, JsonWriter.write(contents), JsonWriter.write(weights));
        });

        List<Item> books = List.of(Item.builder("book", Dimensions.mm("210", "140", "30"))
                .weight(Weight.parse("450 g")).quantity(4).build());
        List<Container> cartons = List.of(
                Container.builder("mailer", Dimensions.mm("220", "150", "130")).costMinor(190).build(),
                Container.builder("carton", Dimensions.mm("400", "300", "250")).costMinor(120).build());

        System.out.print("\n");
        Map<String, Optional<ContainerSelector>> selectors = new LinkedHashMap<>();
        selectors.put("default", Optional.empty());
        selectors.put("smallest box first", Optional.of(new SmallestBoxFirst()));
        selectors.forEach((label, selector) -> {
            ExtensionRegistry registry = new ExtensionRegistry(List.of(), List.of(), List.of(), Optional.empty(),
                    selector);
            PackingResult result = new Packer(config(), registry).pack(books, cartons);
            List<String> opened = result.containers().stream().map(container -> container.container().id()).toList();
            System.out.printf("%18s: opens %s, packaging cost %d\n", label, JsonWriter.write(opened),
                    result.containers().stream().mapToLong(container -> container.container().costMinor()).sum());
        });

        System.out.print("\n");
        System.out.print("None of these rules exists in the JSON request. Hand the same request to another\n");
        System.out.print("Packvium engine and you get the unrestricted answer -- which is exactly why a rule\n");
        System.out.print("everyone must obey belongs in the request as data, not in a class.\n");
    }

    /** Every vase's base height in millimetres, lowest first. */
    private static List<String> vaseHeights(List<PackedContainer> containers) {
        List<String> heights = new ArrayList<>();
        for (PackedContainer container : containers) {
            for (Placement placement : container.placements()) {
                if (placement.instance().item().id().equals("vase")) {
                    heights.add(new Length(placement.position().z()).decimal("mm"));
                }
            }
        }
        heights.sort(Comparator.comparing(BigDecimal::new));
        return heights;
    }
}
