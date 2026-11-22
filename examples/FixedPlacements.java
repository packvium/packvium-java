import com.packvium.serialization.RequestPacker;
import com.packvium.support.RationalParser;
import com.packvium.unit.Weight;
import com.packvium.validation.FixedPlacementException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Pack a trailer that is already partly loaded.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.5.0.jar examples/FixedPlacements.java
 * </pre>
 *
 * <p>A trailer arrives at the depot with four pallets of returns already on board. They are staying
 * on for the next leg, and nobody is going to unload them to make the plan tidier. The depot has
 * fourteen store pallets to add.
 *
 * <p>{@code fixed_placements} describes what is already there: which item, in which container, at
 * which position and orientation -- exactly the way a result reports a placement, so a placement
 * from an earlier plan can be fixed by quoting it. Fixed items are real cargo: they carry weight,
 * count against payload and can support what is stacked on them. The engine packs around them,
 * never moves them, and marks them {@code fixed: true} in the result.
 *
 * <p>A fixed item is also an instance of its item type: {@code quantity} counts the whole order,
 * and the fixed entries take its first instances.
 */
public final class FixedPlacements {
    private static final String RULE = "=".repeat(78);

    private static Map<String, Object> onBoard(String x, String y, String orientation) {
        return object("item_type", "returns-pallet", "container_type", "trailer", "orientation", orientation,
                "position", object("x", x, "y", y, "z", "0"));
    }

    private static Map<String, Object> request() {
        return object(
                "units", object("length", "mm"),
                // Counted work decides the answer, not the clock (see Reproducibility.java); the
                // time limit is a fuse far above what this solve needs.
                "configuration", object("time_limit_ms", 60_000L, "effort_budget", object("max_restarts", 8L)),
                "items", list(
                        object("id", "returns-pallet", "quantity", 4L, "weight", "350 kg", "keep_upright", true,
                                "stackable", false,
                                "dimensions", object("length", "1200", "width", "800", "height", "1500")),
                        object("id", "store-pallet", "quantity", 14L, "weight", "500 kg", "keep_upright", true,
                                "dimensions", object("length", "1200", "width", "800", "height", "1100"))),
                "containers", list(object("id", "trailer", "max_payload", "9000 kg",
                        "inner_dimensions", object("length", "7200", "width", "2400", "height", "2400"))),
                // Three across the nose, and one turned behind them.
                "fixed_placements", list(onBoard("0", "0", "LWH"), onBoard("0", "800", "LWH"),
                        onBoard("0", "1600", "LWH"), onBoard("1200", "0", "WLH")));
    }

    public static void main(String[] arguments) {
        section("1. Packed around the pallets already on board");
        show(solve(request -> { }));
        System.out.print("""

                  The returns pallets keep their ids, positions and orientations. They took the
                  first four instances of `returns-pallet`, so none are left for the engine to
                  place; ask for five and it would place one more.
                """);

        section("2. One pallet was left in the middle of the floor");
        show(solve(request -> fixed(request, 3).put("position", object("x", "1200", "y", "400", "z", "0"))));
        System.out.print("""

                  The fourth returns pallet stands 400 mm off the side wall, and no pallet fits in
                  the strip it leaves. The plan still takes all fourteen store pallets, but it has
                  to put one on top of another to do it. Whether that is acceptable is
                  your call; the plan shows you the price of not moving that pallet.
                """);

        section("3. What is on board cannot be where the record says");
        Map<String, Consumer<Map<String, Object>>> changes = new LinkedHashMap<>();
        changes.put("two pallets recorded in the same place",
                request -> fixed(request, 3).put("position", object("x", "600", "y", "0", "z", "0")));
        changes.put("a 1000 kg trailer limit, already exceeded by the returns",
                request -> map(at(request, "containers", 0)).put("max_payload", "1000 kg"));
        changes.forEach((label, change) -> {
            try {
                solve(change);
            } catch (FixedPlacementException refusal) {
                System.out.printf("  %s:\n", label);
                System.out.printf("    %s, %s: %s\n", refusal.reason(), refusal.field(), refusal.getMessage());
            }
        });
        System.out.print("""

                  Nothing is solved when the starting point is impossible: a plan built on a
                  collision or an overweight trailer would be wrong before the first pallet moved.
                  `cannot_hold` names the fixed set as a whole; the detail says what is wrong.
                """);
    }

    /** Solve a copy of the request with {@code change} applied to it. */
    private static Map<String, Object> solve(Consumer<Map<String, Object>> change) {
        Map<String, Object> request = request();
        change.accept(request);
        return RequestPacker.pack(request);
    }

    private static void show(Map<String, Object> result) {
        Map<?, ?> trailer = (Map<?, ?>) ((List<?>) result.get("containers")).get(0);
        List<Map<?, ?>> fixed = new ArrayList<>();
        List<Map<?, ?>> added = new ArrayList<>();
        for (Object entry : (List<?>) trailer.get("placements")) {
            Map<?, ?> placement = (Map<?, ?>) entry;
            (Boolean.TRUE.equals(placement.get("fixed")) ? fixed : added).add(placement);
        }
        long payloadTicks = (Long) ((Map<?, ?>) trailer.get("payload_weight")).get("ticks");
        System.out.printf("  status %s, payload %s kg, left out %d\n", result.get("status"),
                RationalParser.decimalString(payloadTicks, Weight.TICKS_PER_KG, 1),
                ((List<?>) result.get("unpacked_items")).size());
        System.out.printf("  already on board, unmoved: %s\n",
                fixed.stream().map(placement -> placement.get("item_id").toString()).collect(Collectors.joining(", ")));
        System.out.printf("  added: %d store pallets\n", added.size());
        for (Map<?, ?> placement : added) {
            Object z = ((Map<?, ?>) ((Map<?, ?>) placement.get("position")).get("z")).get("value");
            if (!"0".equals(z)) {
                System.out.printf("    %s is stacked, at z = %s mm\n", placement.get("item_id"), z);
            }
        }
    }

    private static void section(String title) {
        System.out.printf("\n%s\n%s\n%s\n", RULE, title, RULE);
    }

    /** An ordered, mutable JSON object from alternating keys and values. */
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], pairs[index + 1]);
        }
        return out;
    }

    private static List<Object> list(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    private static Object at(Map<String, Object> request, String key, int index) {
        return ((List<?>) request.get(key)).get(index);
    }

    private static Map<String, Object> fixed(Map<String, Object> request, int index) {
        return map(at(request, "fixed_placements", index));
    }

    // Every object here was built by `object`, so each is a Map<String, Object>.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
