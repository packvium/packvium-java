import com.packvium.Packer;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Axle;
import com.packvium.domain.AxlePair;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.result.PackingResult;
import com.packvium.sequence.LoadingDependencyGraph;
import com.packvium.sequence.Reachability;
import com.packvium.sequence.RouteSequenceError;
import com.packvium.sequence.UnloadingDependencyGraph;
import com.packvium.serialization.ResultEncoder;
import com.packvium.support.RationalParser;
import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Load a delivery van for a three-stop route, within its axle ratings.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.7.0.jar examples/Trucking.java
 * </pre>
 *
 * <p>A van is not a big box. Its payload rides on two axles, and each axle has its own rating that
 * the total payload limit does not protect: a van can be under its payload and still overload the
 * front axle. Its cargo also comes out through one door, in the order the stops come up, so the
 * goods for the first stop must not be walled in by the goods for the last one.
 *
 * <p>Three request fields carry that:
 *
 * <ul>
 *   <li>{@code axles} on the container: front and rear, each a position along the length and an
 *       optional maximum load. Checked for every placement against the gross load (payload plus
 *       tare), so no plan puts either axle over its rating.
 *   <li>{@code accessDirections} on the container: the walls cargo can leave through. {@code +x}
 *       is the wall at the far end of the length axis -- here, the rear doors.
 *   <li>{@code stopIndex} on each item: which stop it is delivered at, {@code 0} first.
 * </ul>
 *
 * <p>After the solve, {@code com.packvium.sequence} answers the questions a driver asks: in what
 * order do things come off, in what order must they go on, and what can I reach when I open the
 * doors at the first stop?
 */
public final class Trucking {
    private static final String RULE = "=".repeat(78);
    private static final List<String> REAR_DOORS = List.of("+x");

    // Length runs from the cab bulkhead (x = 0) to the rear doors. The axle positions are measured
    // along that same axis, which is what lets the engine take moments about them.
    private static final Axle FRONT_AXLE = new Axle(Length.mm("600"), Optional.of(Weight.parse("1650 kg")));
    private static final Axle REAR_AXLE = new Axle(Length.mm("3500"), Optional.of(Weight.parse("2200 kg")));

    // The effort budget decides where the search stops, so every run gives this same plan; the
    // time limit is only a fuse far above what this needs (see Reproducibility.java).
    // `maxContainers(1)` because there is one van: anything that does not fit is reported, not
    // sent in a second vehicle.
    private static final PackingConfig CONFIG = PackingConfig.builder(SolverProfile.BALANCED).timeLimitMs(60_000)
            .maxContainers(1L)
            .effortBudget(new EffortBudget(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(8L)))
            .build();

    private static Container van(Axle front, Axle rear) {
        return Container.builder("van", Dimensions.mm("4200", "1800", "1900"))
                // Tare matters here: axle ratings are gross limits, and the empty van already puts
                // most of the front rating to use before anything is loaded.
                .tareWeight(Weight.parse("2400 kg")).maxPayload(Weight.parse("1300 kg"))
                .axles(new AxlePair(front, rear)).accessDirections(REAR_DOORS).build();
    }

    private static List<Item> manifest(boolean withRoute) {
        return List.of(
                item("bakery-rack", "800", "600", "1700", "90 kg", 2, withRoute ? 0L : null).keepUpright(true)
                        .build(),
                item("drinks-pallet", "1200", "800", "1100", "420 kg", 1, withRoute ? 1L : null).keepUpright(true)
                        .mustBeOnFloor(true).build(),
                item("parcel", "600", "400", "400", "18 kg", 6, withRoute ? 2L : null).build(),
                item("dry-goods-pallet", "1200", "800", "1400", "260 kg", 1, withRoute ? 2L : null)
                        .keepUpright(true).build());
    }

    /** Every orientation is offered and {@code keepUpright} narrows it. */
    private static Item.Builder item(String id, String length, String width, String height, String weight,
            long quantity, Long stop) {
        return Item.builder(id, Dimensions.mm(length, width, height)).weight(Weight.parse(weight)).quantity(quantity)
                .allowedRotations(Rotation.all()).stopIndex(stop);
    }

    public static void main(String[] arguments) {
        section("1. The plan: nothing for a later stop stands in an earlier stop's way out");
        PackingResult result = new Packer(CONFIG).pack(manifest(true), List.of(van(FRONT_AXLE, REAR_AXLE)));
        PackedContainer packed = result.containers().get(0);
        List<Placement> placements = packed.placements();
        System.out.printf("  status: %s, left behind: %d\n", result.status().wireName(), result.unpacked().size());
        List<Placement> byPosition = new ArrayList<>(placements);
        byPosition.sort(Comparator.<Placement>comparingLong(p -> p.position().x())
                .thenComparingLong(p -> p.position().y()).thenComparingLong(p -> p.position().z()));
        for (Placement placement : byPosition) {
            System.out.printf("    stop %d  %-20s x=%6s mm  y=%6s mm\n",
                    placement.instance().item().stopIndex().orElseThrow(), placement.instance().id(),
                    millimetres(placement.position().x()), millimetres(placement.position().y()));
        }
        System.out.print("""

                  x is the distance from the bulkhead, so the rear doors are at x = 4200 mm. The
                  bakery racks sit at the front, but the aisle beside them runs clear to the doors:
                  the rule is that nothing due later blocks every way out, not that stop 0 is last in.
                """);

        section("2. Axle loads against their ratings");
        String[] loads = axleLoadsKg(result);
        System.out.printf("  payload: %s kg of 1300 kg allowed\n", kilograms(packed.payloadWeight()));
        System.out.printf("  front axle: %s kg of %s kg\n", loads[0], kilograms(FRONT_AXLE.maxLoad().orElseThrow()));
        System.out.printf("  rear axle:  %s kg of %s kg\n", loads[1], kilograms(REAR_AXLE.maxLoad().orElseThrow()));

        Axle tightAxle = new Axle(Length.mm("600"), Optional.of(Weight.parse("1450 kg")));
        PackingResult tight = new Packer(CONFIG).pack(manifest(true), List.of(van(tightAxle, REAR_AXLE)));
        System.out.print("\n  the same load with a 1450 kg front axle rating:\n");
        for (UnpackedItem unpacked : tight.unpacked()) {
            System.out.printf("    left behind: %s, reason %s (%s)\n", unpacked.instance().id(), unpacked.reason(),
                    unpacked.proof().level());
        }
        System.out.print("""

                  The axle rule refuses a placement rather than reporting an overload afterwards.
                  Note the reason: an item the axles cannot carry is reported as the generic
                  `no_feasible_placement`, not as an axle problem, so when a load comes up short on
                  a vehicle with axle ratings, check the reactions before shopping for a bigger van.
                """);

        section("3. Unloading order along the route, and the loading order it implies");
        List<AxisAlignedBox> boxes = placements.stream().map(Placement::envelopeBox).toList();
        List<Optional<Long>> stops = placements.stream().map(p -> p.instance().item().stopIndex()).toList();
        Dimensions inside = packed.container().innerDimensions();

        List<Integer> unloading = UnloadingDependencyGraph.safeRouteRemovalOrder(boxes, stops, inside, REAR_DOORS);
        for (int step = 0; step < unloading.size(); step++) {
            int index = unloading.get(step);
            System.out.printf("    off %2d: stop %d  %s\n", step + 1, stops.get(index).orElseThrow(),
                    placements.get(index).instance().id());
        }

        // Loading is unloading played backwards: whatever comes off last goes on first.
        // `replayLoadingOrder` checks that independently -- every item supported by what is
        // already in, and each one able to travel in through the doors -- and throws if not.
        List<Integer> loading = new ArrayList<>(unloading);
        Collections.reverse(loading);
        LoadingDependencyGraph.replayLoadingOrder(boxes, inside, loading, REAR_DOORS);
        System.out.printf("\n  load in this order: %s\n", loading.stream()
                .map(index -> placements.get(index).instance().id()).collect(Collectors.joining(", ")));

        section("4. What the driver can reach on opening the doors at stop 0");
        for (Reachability reach : UnloadingDependencyGraph.placementReachability(boxes, inside, stops, REAR_DOORS)) {
            if (!stops.get(reach.index()).equals(Optional.of(0L))) {
                continue;
            }
            TreeSet<String> blockers = new TreeSet<>();
            reach.blockedByNeighbors().forEach(index -> blockers.add(placements.get(index).instance().id()));
            reach.blockedBySupport().forEach(index -> blockers.add(placements.get(index).instance().id()));
            System.out.printf("    %s: reachable=%s%s\n", placements.get(reach.index()).instance().id(),
                    reach.reachable(), blockers.isEmpty() ? "" : ", behind " + String.join(", ", blockers));
        }
        System.out.print("""

                  Reachability is a snapshot, not a plan: one rack is behind the other, which is
                  fine because both are for this stop and the first one comes off first.
                """);

        section("5. The same goods without `stopIndex`");
        PackingResult unrouted = new Packer(CONFIG).pack(manifest(false), List.of(van(FRONT_AXLE, REAR_AXLE)));
        List<Placement> unroutedPlacements = unrouted.containers().get(0).placements();
        Map<String, Long> due = Map.of("bakery-rack", 0L, "drinks-pallet", 1L, "parcel", 2L, "dry-goods-pallet", 2L);
        try {
            UnloadingDependencyGraph.safeRouteRemovalOrder(
                    unroutedPlacements.stream().map(Placement::envelopeBox).toList(),
                    unroutedPlacements.stream().map(p -> Optional.of(due.get(p.instance().item().id()))).toList(),
                    inside, REAR_DOORS);
        } catch (RouteSequenceError stuck) {
            TreeSet<String> names = new TreeSet<>();
            stuck.stuck().forEach(index -> names.add(unroutedPlacements.get(index).instance().id()));
            System.out.printf("  status %s, every item packed -- and at stop %d the driver cannot get these out: %s\n",
                    unrouted.status().wireName(), stuck.stop(), String.join(", ", names));
        }
        System.out.print("""

                  Without the route the plan is still a valid packing, and still within the axle
                  ratings; it just cannot be delivered in order. The route is only enforced when
                  you state it.
                """);
    }

    private static void section(String title) {
        System.out.printf("\n%s\n%s\n%s\n", RULE, title, RULE);
    }

    private static String millimetres(long ticks) {
        return RationalParser.decimalString(ticks, Length.TICKS_PER_MM, 1);
    }

    private static String kilograms(Weight weight) {
        return RationalParser.decimalString(weight.ticks(), Weight.TICKS_PER_KG, 1);
    }

    /**
     * The exact axle reactions from the result document, shown in kilograms.
     *
     * <p>They arrive as a numerator over a shared denominator so no engine has to round them;
     * rounding happens here, for display only, half to even like every other value Packvium
     * renders. The numerator is a decimal string because weight ticks times length ticks outgrows
     * a 64-bit integer.
     */
    private static String[] axleLoadsKg(PackingResult result) {
        Map<String, Object> document = new ResultEncoder("mm", "g").encode(result);
        Map<?, ?> reactions = (Map<?, ?>) ((Map<?, ?>) ((List<?>) document.get("containers")).get(0))
                .get("axle_reactions");
        BigInteger denominator = new BigInteger(reactions.get("denominator").toString());
        return new String[] {
            kilograms(rounded(new BigInteger(reactions.get("front_numerator").toString()), denominator)),
            kilograms(rounded(new BigInteger(reactions.get("rear_numerator").toString()), denominator)),
        };
    }

    private static Weight rounded(BigInteger numerator, BigInteger denominator) {
        BigInteger[] division = numerator.divideAndRemainder(denominator);
        int twice = division[1].shiftLeft(1).compareTo(denominator);
        boolean up = twice > 0 || (twice == 0 && division[0].testBit(0));
        return new Weight((up ? division[0].add(BigInteger.ONE) : division[0]).longValueExact());
    }
}
