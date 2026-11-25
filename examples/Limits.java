import com.packvium.serialization.RequestPacker;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Fleet and vehicle limits: what may go where, and how much of it.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.6.0.jar examples/Limits.java
 * </pre>
 *
 * <p>A same-day courier has one refrigerated van and a few cargo bikes. Every rule below is a
 * request field, so it holds in every Packvium engine and travels with the request:
 *
 * <ul>
 *   <li>{@code obstacles} on a container: space inside it that cargo may not use -- here the fridge
 *       unit under the roof, and the two wheel arches as one obstacle made of two boxes
 *       ({@code additional_boxes}), since an arch on each side is one thing to the person loading.
 *   <li>{@code max_items} on a container: a cap on how many items it takes, whatever their size --
 *       a bike's rack holds four parcels however small they are.
 *   <li>{@code tags} and {@code tag_limits}: at most one item tagged {@code dangerous_goods} in the
 *       van.
 *   <li>{@code eligible_container_tags} on an item: frozen goods only go in a container tagged
 *       {@code refrigerated}.
 *   <li>{@code max_containers} in the configuration: how many vehicles the whole plan may use.
 * </ul>
 */
public final class Limits {
    private static final String RULE = "=".repeat(78);

    private static Map<String, Object> request() {
        return object(
                "units", object("length", "mm"),
                // Counted work decides the answer, not the clock (see Reproducibility.java); the
                // time limit is a fuse far above what this solve needs.
                "configuration", object("time_limit_ms", 60_000L, "effort_budget", object("max_restarts", 8L)),
                "items", list(
                        object("id", "frozen-tote", "quantity", 3L, "weight", "15 kg",
                                "dimensions", object("length", "600", "width", "400", "height", "300"),
                                "eligible_container_tags", list("refrigerated")),
                        object("id", "lithium-battery", "quantity", 3L, "weight", "12 kg",
                                "dimensions", object("length", "400", "width", "300", "height", "250"),
                                "tags", list("dangerous_goods")),
                        object("id", "parcel", "quantity", 24L, "weight", "8 kg",
                                "dimensions", object("length", "500", "width", "400", "height", "400"))),
                "containers", list(
                        object("id", "cargo-bike", "quantity", 3L, "cost_minor", 900L, "max_items", 4L,
                                "max_payload", "100 kg",
                                "inner_dimensions", object("length", "1000", "width", "800", "height", "900")),
                        object("id", "reefer-van", "quantity", 1L, "cost_minor", 4000L,
                                "inner_dimensions", object("length", "1800", "width", "1400", "height", "1200"),
                                "tags", list("refrigerated"),
                                "tag_limits", object("dangerous_goods", 1L),
                                "obstacles", list(
                                        object("id", "fridge-unit", "origin", object("x", "0", "y", "0", "z", "800"),
                                                "dimensions", object("length", "400", "width", "1400",
                                                        "height", "400")),
                                        object("id", "wheel-arches", "origin", object("x", "700", "y", "0", "z", "0"),
                                                "dimensions", object("length", "800", "width", "200",
                                                        "height", "350"),
                                                "additional_boxes", list(object(
                                                        "origin", object("x", "700", "y", "1200", "z", "0"),
                                                        "dimensions", object("length", "800", "width", "200",
                                                                "height", "350"))))))));
    }

    public static void main(String[] arguments) {
        section("1. The plan");
        Map<String, Object> plan = solve(request -> { });
        show(plan);
        System.out.print("""

                  All three frozen totes are in the van, because nothing else is refrigerated. Only
                  one battery rides in the van; the other two go by bike. The bike takes four items,
                  its `max_items`, although there is room and payload for more.
                """);

        section("2. What the obstacles cost");
        Map<String, Object> openVan = solve(request -> container(request, 1).remove("obstacles"));
        System.out.printf("  parcels in the van with the fridge unit and arches: %d\n",
                placedCount(plan, "reefer-van#1", "parcel"));
        System.out.printf("  parcels in the van if it were an empty box:        %d\n",
                placedCount(openVan, "reefer-van#1", "parcel"));
        System.out.print("""

                  An obstacle is exact space, not a hint. Model what is really there, or the plan
                  will put a parcel where the wheel arch is.
                """);

        section("3. `max_containers`: the plan may use one vehicle");
        show(solve(request -> map(request.get("configuration")).put("max_containers", 1L)));
        System.out.print("""

                  The cap is honoured, and what does not fit is listed rather than silently sent in
                  a vehicle you did not allow. The reason is `observed`, not `proven`: the search
                  found no room for these, which is not a claim that no arrangement has room.
                """);

        section("4. `eligible_container_tags`: the van is off the road today");
        show(solve(request -> ((List<?>) request.get("containers")).remove(1)));
        System.out.print("""

                  The frozen totes cannot go anywhere, and their reason is `proven`: no container in
                  the request carries the tag they need, which is a fact about the request rather
                  than about how far the search got. The rest is `observed`: three bikes of four
                  items each were all the request offered, and the search ran out of them.
                """);
    }

    /** Solve a copy of the request with {@code change} applied to it. */
    private static Map<String, Object> solve(Consumer<Map<String, Object>> change) {
        Map<String, Object> request = request();
        change.accept(request);
        return RequestPacker.pack(request);
    }

    private static void show(Map<String, Object> result) {
        System.out.printf("  status: %s\n", result.get("status"));
        for (Object entry : (List<?>) result.get("containers")) {
            Map<?, ?> container = (Map<?, ?>) entry;
            Map<String, Integer> contents = new TreeMap<>(SortKey::compareCodePoints);
            for (Object placement : (List<?>) container.get("placements")) {
                contents.merge(((Map<?, ?>) placement).get("item_type").toString(), 1, Integer::sum);
            }
            List<String> listed = new ArrayList<>();
            contents.forEach((name, count) -> listed.add(count + " " + name));
            System.out.printf("    %-14s %s\n", container.get("id"), String.join(", ", listed));
        }
        // One line per kind of refusal: a reader needs "five parcels, no room", not five lines.
        Map<List<String>, Integer> refusals = new TreeMap<>(Limits::compareKeys);
        for (Object entry : (List<?>) result.get("unpacked_items")) {
            Map<?, ?> unpacked = (Map<?, ?>) entry;
            List<String> key = List.of(unpacked.get("item_type").toString(), unpacked.get("reason").toString(),
                    ((Map<?, ?>) unpacked.get("proof")).get("level").toString());
            refusals.merge(key, 1, Integer::sum);
        }
        refusals.forEach((key, number) -> System.out.printf("    left out: %d %s, %s (%s)\n", number, key.get(0),
                key.get(1), key.get(2)));
    }

    /** Field by field, the order a sort of the fields joined by a NUL gives. */
    private static int compareKeys(List<String> left, List<String> right) {
        for (int index = 0; index < left.size(); index++) {
            int order = SortKey.compareCodePoints(left.get(index), right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static int placedCount(Map<String, Object> result, String containerId, String itemType) {
        int total = 0;
        for (Object entry : (List<?>) result.get("containers")) {
            Map<?, ?> container = (Map<?, ?>) entry;
            if (!container.get("id").equals(containerId)) {
                continue;
            }
            for (Object placement : (List<?>) container.get("placements")) {
                if (((Map<?, ?>) placement).get("item_type").equals(itemType)) {
                    total++;
                }
            }
        }
        return total;
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

    private static Map<String, Object> container(Map<String, Object> request, int index) {
        return map(((List<?>) request.get("containers")).get(index));
    }

    // Every object here was built by `object`, so each is a Map<String, Object>.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
