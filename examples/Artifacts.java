import com.packvium.artifacts.ArtifactExports;
import com.packvium.artifacts.OperationalArtifact;
import com.packvium.artifacts.OperationalArtifactException;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;
import com.packvium.sequence.LoadingDependencyGraph;
import com.packvium.serialization.RequestPacker;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand a packing result to a warehouse system that has no engine.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.5.0.jar examples/Artifacts.java
 * </pre>
 *
 * <p>An execution plan says what to lift first. A warehouse or transport system needs more than
 * that before it can act on its own: how big each box is, what the load weighs, a sheet to print
 * for the dock, and a record of which request and solver produced all of it. Without those, it
 * has to call the engine again.
 *
 * <p>The operational artifact is that one document. It wraps the plan unchanged and adds geometry,
 * display values and provenance. It exports to JSON, CSV and a printable HTML work order, and none
 * of that calls a solver, a renderer or a clock. Four engines build the same bytes from it; this
 * example follows the Python one.
 */
public final class Artifacts {
    private static final String RULE = "=".repeat(78);

    public static void main(String[] arguments) {
        Map<String, Object> request = Map.of(
                "units", Map.of("length", "mm"),
                // A fuse far above what this solve needs, so the answer never depends on machine load.
                "configuration", Map.of("time_limit_ms", 60_000L),
                "items", List.of(
                        Map.of("id", "printer", "dimensions", Map.of("length", 420L, "width", 300L, "height", 250L),
                                "weight", "12 kg", "metadata", Map.of("sales_order", "SO-1042")),
                        Map.of("id", "toner", "quantity", 3L,
                                "dimensions", Map.of("length", 300L, "width", 100L, "height", 100L),
                                "weight", "1.5 kg", "minimum_support_ratio", new BigDecimal("0.5"))),
                "containers", List.of(Map.of("id", "crate",
                        "inner_dimensions", Map.of("length", 800L, "width", 400L, "height", 400L))));

        Map<String, Object> result = RequestPacker.pack(request);

        section("1. One document that carries everything a consumer needs");
        Map<?, ?> crate = (Map<?, ?>) ((List<?>) result.get("containers")).get(0);
        List<AxisAlignedBox> boxes = new ArrayList<>();
        for (Object placement : (List<?>) crate.get("placements")) {
            boxes.add(box((Map<?, ?>) placement));
        }
        // The order boxes can go in without lifting one over another comes from the engine's own
        // sequence API. The artifact is handed that order; it never invents one.
        List<Integer> order = LoadingDependencyGraph.safeLoadingOrder(boxes, ticks((Map<?, ?>) crate.get("inner_dimensions")));
        Map<String, Object> artifact = OperationalArtifact.build(request, result, Map.of(0, order));
        Object provenance = artifact.get("provenance");
        System.out.printf("  format:          %s\n", artifact.get("format"));
        System.out.printf("  plan steps:      %d\n", ((List<?>) at(artifact, "plan", "containers", 0, "steps")).size());
        System.out.printf("  crate inside:    %s ticks long\n", at(artifact, "geometry", "containers", 0, "inner_dimensions", "length"));
        System.out.printf("  replay:          %s\n", at(provenance, "replay", "level"));
        System.out.printf("  sales order:     %s\n", at(provenance, "request", "items", 0, "metadata", "sales_order"));
        System.out.print("\n");
        System.out.print("  The request is inside the artifact, not a hash of it: a digest names a request,\n");
        System.out.print("  and only the request itself lets someone replay the artifact without a lookup.\n");
        System.out.print("  `replay` is `exact` because the search finished inside its fuse; a search the\n");
        System.out.print("  clock stopped would say `not_guaranteed` and name the field that decided it.\n");

        section("2. A CSV a warehouse system can import");
        String[] rows = ArtifactExports.csv(artifact).split("\r\n", -1);
        for (int row = 0; row < 3; row++) {
            System.out.printf("  %s\n", rows[row]);
        }
        System.out.print("\n");
        System.out.print("  One row per step, in the plan's order, then one row per item that was not\n");
        System.out.print("  packed. The tick columns are the identifiers a system matches on; the rendered\n");
        System.out.print("  columns are for people. Nothing is re-rendered: every value is the result's own.\n");

        section("3. A work order to print");
        String html = ArtifactExports.workOrderHtml(artifact);
        System.out.printf("  %d bytes of HTML, one section per container, one checkbox per step\n",
                html.getBytes(StandardCharsets.UTF_8).length);
        System.out.printf("  contains a script or an external resource: %s\n", html.contains("<script") || html.contains("http"));
        System.out.print("\n");
        System.out.print("  Self-contained on purpose: a work order that loads a stylesheet from the network\n");
        System.out.print("  is a work order that prints blank on a dock with no signal.\n");

        section("4. The same bytes in every engine, and a refusal instead of a guess");
        String canonical = ArtifactExports.json(artifact);
        System.out.printf("  canonical JSON: %d bytes, starting %s...\n", canonical.getBytes(StandardCharsets.UTF_8).length,
                canonical.substring(0, 40));
        Map<String, Object> foreign = new LinkedHashMap<>(artifact);
        foreign.put("format", "packvium-operational-artifact/v2");
        try {
            ArtifactExports.csv(foreign);
        } catch (OperationalArtifactException error) {
            System.out.printf("  a v2 document: refused with %s\n", error.errorCode());
        }
        System.out.print("\n");
        System.out.print("  The canonical form is RFC 8785, so PHP, Rust and JavaScript build these exact\n");
        System.out.print("  bytes from the same request and result. A reader that meets a format it does not\n");
        System.out.print("  know refuses it by name rather than drawing half of it.\n");
    }

    private static AxisAlignedBox box(Map<?, ?> placement) {
        Map<?, ?> position = (Map<?, ?>) placement.get("position");
        return new AxisAlignedBox(new Point(tick(position, "x"), tick(position, "y"), tick(position, "z")),
                ticks((Map<?, ?>) placement.get("dimensions")));
    }

    private static Dimensions ticks(Map<?, ?> size) {
        return Dimensions.ofTicks(tick(size, "length"), tick(size, "width"), tick(size, "height"));
    }

    private static long tick(Map<?, ?> scalars, String axis) {
        return (Long) ((Map<?, ?>) scalars.get(axis)).get("ticks");
    }

    private static Object at(Object tree, Object... path) {
        Object node = tree;
        for (Object step : path) {
            node = step instanceof Integer index ? ((List<?>) node).get(index) : ((Map<?, ?>) node).get(step);
        }
        return node;
    }

    private static void section(String title) {
        System.out.print("\n" + RULE + "\n" + title + "\n" + RULE + "\n");
    }
}
