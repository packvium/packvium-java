import com.packvium.serialization.RequestPacker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes: when an item is not its box.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.0.0.jar examples/Shapes.java
 * </pre>
 *
 * <p>Every other example treats an item as the box it declares. That is the default and it is
 * right for almost everything, because a carton <em>is</em> a cuboid. Two kinds of goods are
 * not: a moulded or tapered part that leaves a usable void beside it, and a soft one that gives
 * way under whatever is stacked on it.
 *
 * <p>{@code shape_type} narrows the box in one direction each -- {@code convex_hull} in space,
 * {@code compressible} in height under load -- and neither is ever inferred. An engine that
 * quietly packed a hull as its bounding box would return a plan that validates and does not
 * physically fit, so the value must be asked for.
 *
 * <p>Both are written here through {@code RequestPacker.pack}, the request contract the four
 * engines share, so the same document runs unchanged against the Python, Rust and JavaScript
 * engines.
 */
public final class Shapes {
    // ------------------------------------------------------------------ convex_hull
    //
    // Two triangular prisms, each cut from the same 100 mm cube along the diagonal. Their
    // bounding boxes are identical and fill the crate on their own, so as cuboids the second one
    // has nowhere to go. As hulls they are complementary halves and share the crate exactly --
    // the collision test is an exact integer separating-axis test on the vertices, not a box
    // overlap.
    //
    // The hull is given in the item's own coordinates, in the request's length unit, and must
    // fit inside the declared dimensions. It is not a replacement for them: the box still bounds
    // the item, the hull only says how much of that box is solid.

    private static final List<Map<String, String>> LOWER_WEDGE = List.of(
            vertex("0", "0", "0"), vertex("100", "0", "0"),
            vertex("0", "100", "0"), vertex("0", "0", "100"),
            vertex("100", "0", "100"), vertex("0", "100", "100"));
    private static final List<Map<String, String>> UPPER_WEDGE = List.of(
            vertex("100", "100", "0"), vertex("100", "0", "0"),
            vertex("0", "100", "0"), vertex("100", "100", "100"),
            vertex("100", "0", "100"), vertex("0", "100", "100"));

    private static final Map<String, String> CUBE = Map.of("length", "100", "width", "100", "height", "100");

    public static void main(String[] arguments) {
        System.out.print("convex_hull -- two complementary wedges cut from one cube\n");
        summarise("as cuboids", request(
                List.of(wedge("wedge-lower"), wedge("wedge-upper")),
                crate("100", "100", "100")));
        summarise("as hulls", request(
                List.of(wedge("wedge-lower", LOWER_WEDGE), wedge("wedge-upper", UPPER_WEDGE)),
                crate("100", "100", "100")));

        // One crate instead of two, for the same goods and the same crate. Nothing about the
        // request changed except the claim that the items are wedges rather than blocks.

        // The crate is 100x100x200 and the two items are 100 mm cubes, so rigidly they fill it
        // exactly and nothing is unused. Under 101 kg the cushion gives up part of its quarter,
        // the pair still ships as one stack, and the volume it stopped occupying shows up as
        // unused. One more kilogram crosses 100 kPa over the cushion's 0.01 m^2 face: the stack
        // is refused, the brick opens a second crate, and half of each crate is empty.
        System.out.print("\ncompressible -- a cushion that yields to the load above it\n");
        load("brick 101 kg", 101);
        load("brick 102 kg", 102);

        // Both shapes are refused rather than approximated wherever an engine cannot honour them
        // exactly -- a hull on a route, a hull under a configured clearance, a compressible item
        // with `nesting_height`. A wrong answer that validates is worse than a refusal that does
        // not, which is the whole reason these are opt-in.
    }

    private static Map<String, String> vertex(String x, String y, String z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static Map<String, Object> request(List<Map<String, Object>> items, List<Map<String, Object>> containers) {
        return Map.of("units", Map.of("length", "mm"), "items", items, "containers", containers);
    }

    private static List<Map<String, Object>> crate(String length, String width, String height) {
        return List.of(Map.of("id", "crate",
                "inner_dimensions", Map.of("length", length, "width", width, "height", height)));
    }

    /** A 100 mm, 1 kg cube, declared as a plain cuboid. */
    private static Map<String, Object> wedge(String id) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("quantity", 1L);
        item.put("dimensions", CUBE);
        item.put("weight", Map.of("value", "1", "unit", "kg"));
        return item;
    }

    /** The same cube, declared as the solid part of it the vertices enclose. */
    private static Map<String, Object> wedge(String id, List<Map<String, String>> vertices) {
        Map<String, Object> item = wedge(id);
        item.put("shape_type", "convex_hull");
        item.put("hull_vertices", vertices);
        return item;
    }

    /** Run one request and print only what the shape changed: containers and refusals. */
    private static void summarise(String label, Map<String, Object> request) {
        Map<String, Object> result = RequestPacker.pack(request);
        List<?> containers = (List<?>) result.get("containers");
        int placed = containers.stream().mapToInt(container -> placements(container).size()).sum();
        System.out.printf("  %-22s %-10s %d container(s), %d placed, %d refused\n",
                label, result.get("status"), containers.size(), placed,
                ((List<?>) result.get("unpacked_items")).size());
    }

    private static List<?> placements(Object container) {
        return (List<?>) ((Map<?, ?>) container).get("placements");
    }

    // ----------------------------------------------------------------- compressible
    //
    // `compression_ratio` is the fraction of its own height an item may lose when something
    // rests on it -- 0.25 means it can give up a quarter. The mass above it is what decides how
    // much it actually gives, so the occupied height of a compressible item is not a property
    // of the item alone; it depends on what the solver put on top.
    //
    // `max_compression_pressure_kpa` is the other half of the same field. Past that pressure the
    // item is not compressed further, it is crushed, and the load is refused instead.
    //
    // Note `must_be_on_floor` on the cushion. Without it the solver is free to put the brick
    // underneath, nothing bears on the cushion, and the feature never engages -- which is the
    // honest reason the rule is here and not an incidental detail of the example.

    private static Map<String, Object> cushion(long crushKpa) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "cushion");
        item.put("quantity", 1L);
        item.put("dimensions", CUBE);
        item.put("weight", Map.of("value", "2", "unit", "kg"));
        item.put("must_be_on_floor", true);
        item.put("shape_type", "compressible");
        item.put("compression_ratio", 0.25);
        item.put("max_compression_pressure_kpa", crushKpa);
        return item;
    }

    private static Map<String, Object> brick(long kilograms) {
        return Map.of("id", "brick", "quantity", 1L, "dimensions", CUBE,
                "weight", Map.of("value", Long.toString(kilograms), "unit", "kg"));
    }

    /** One crate, one cushion, one brick -- only the brick's mass changes. */
    private static void load(String label, long kilograms) {
        Map<String, Object> result = RequestPacker.pack(request(
                List.of(cushion(100), brick(kilograms)),
                crate("100", "100", "200")));
        System.out.printf("  %-22s %d container(s), unused volume %d ppm\n",
                label, ((List<?>) result.get("containers")).size(), ((List<?>) result.get("score")).get(3));
    }
}
