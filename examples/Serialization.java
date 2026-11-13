import com.packvium.serialization.JsonReader;
import com.packvium.serialization.JsonWriter;
import com.packvium.serialization.RequestPacker;
import com.packvium.serialization.UnsupportedFeatureException;
import com.packvium.serialization.UnsupportedFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialization: the same request as JSON, and what comes back.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.1.0.jar examples/Serialization.java
 * </pre>
 *
 * <p>Everything the library can do is reachable over one JSON document, and that is not a
 * convenience wrapper -- it is the contract four independent implementations are held to. The
 * PHP, Python, Rust and JavaScript engines read this exact shape and are checked against each
 * other on a shared fixture corpus, so a request you build here is a request you can hand to
 * any of them.
 *
 * <p>Two consequences worth knowing:
 *
 * <ul>
 *   <li>lengths and weights travel as <em>decimal strings</em>, never as floats, so "12 3/8 in"
 *       survives the trip intact (see Units.java for why that matters);
 *   <li>a field this engine has deliberately not implemented is refused by name, but a key the
 *       parser simply does not recognise is ignored. The last section shows both, and the
 *       refusal of a field this engine has caught up on through the guard's own test hook.
 * </ul>
 */
public final class Serialization {
    // ---------------------------------------------------------------------------------------
    // A request is one JSON document. This one is the whole vocabulary in miniature: units,
    // solver configuration, items with rules, and a container with a carrier rate card.
    //
    // `time_limit_ms` is there because an example must not change answer merely because the
    // machine is busy: how many runners-up `top_k` finds is bounded by the wall clock unless a
    // budget says otherwise. The value is a safety fuse, not a target.
    // ---------------------------------------------------------------------------------------
    private static final String REQUEST = """
            {
              "units": {"length": "mm"},
              "configuration": {
                "objective": "lowest_landed_cost",
                "dimensional_weight_divisor": 5000,
                "dimensional_weight_length_unit": "cm",
                "dimensional_weight_weight_unit": "kg",
                "profile": "balanced",
                "seed": 42,
                "top_k": 2,
                "time_limit_ms": 60000
              },
              "items": [
                {
                  "id": "book", "quantity": 6,
                  "dimensions": {"length": "210", "width": "140", "height": "30"},
                  "weight": "450 g"
                },
                {
                  "id": "mug", "quantity": 2,
                  "dimensions": {"length": "100", "width": "100", "height": "120"},
                  "weight": "380 g",
                  "keep_upright": true,
                  "max_top_load": "1 kg"
                }
              ],
              "containers": [
                {
                  "id": "box-m",
                  "inner_dimensions": {"length": "400", "width": "300", "height": "250"},
                  "max_payload": "20 kg",
                  "cost_minor": 180,
                  "rate_table": {
                    "weight_brackets_g": [5000, 10000, 30000],
                    "prices_minor": [890, 1240, 2050],
                    "minimum_charge_minor": 650,
                    "fuel_surcharge_permille": 78
                  }
                }
              ]
            }
            """;

    public static void main(String[] arguments) {
        Map<?, ?> request = (Map<?, ?>) JsonReader.parse(REQUEST);
        Map<String, Object> result = RequestPacker.pack(request);

        // -----------------------------------------------------------------------------------
        // The result is a map too, and deliberately verbose: every placement has exact
        // coordinates, every unplaced item has a structured reason, and the algorithm report
        // says which solver won and what it spent getting there.
        // -----------------------------------------------------------------------------------
        List<Map<?, ?>> containers = objects(result.get("containers"));
        int placed = containers.stream().mapToInt(container -> objects(container.get("placements")).size()).sum();
        System.out.printf("status      %s\n", result.get("status"));
        System.out.printf("score       %s  <- lexicographic, exact integers, cheapest first\n",
                JsonWriter.write(result.get("score")));
        System.out.printf("solver      %s\n", ((Map<?, ?>) result.get("algorithm")).get("solver"));
        System.out.printf("containers  %s\n",
                JsonWriter.write(containers.stream().map(container -> container.get("container_type")).toList()));
        System.out.printf("placed      %d\n", placed);
        System.out.printf("unplaced    %s\n", JsonWriter.write(result.get("unpacked_items")));

        System.out.print("\none placement, in full:\n");
        Map<?, ?> firstPlacement = objects(containers.get(0).get("placements")).get(0);
        System.out.print(prefix(prettyJson(firstPlacement), 320) + " ...\n");

        // -----------------------------------------------------------------------------------
        // `top_k` asks for runners-up: real alternative arrangements, already scored and
        // already validated. Two of them can share a score and still differ in geometry, so
        // compare placements rather than scores when showing a human a choice.
        // -----------------------------------------------------------------------------------
        List<Map<?, ?>> alternatives = objects(result.get("alternatives"));
        System.out.print("\nalternatives: " + alternatives.size() + "\n");
        for (Map<?, ?> alternative : alternatives) {
            Map<?, ?> first = objects(objects(alternative.get("containers")).get(0).get("placements")).get(0);
            Map<?, ?> x = (Map<?, ?>) ((Map<?, ?>) first.get("position")).get("x");
            System.out.printf("   score %s  first placement %s at x=%s\n",
                    JsonWriter.write(alternative.get("score")), first.get("item_id"), x.get("value"));
        }

        // -----------------------------------------------------------------------------------
        // What is refused, and what is not. The two look alike from the outside.
        //
        // A key the parser does not recognise is *ignored*. Misspell `keep_upright` and you
        // get a silently unrotated mug, not an error -- the strictness lives in the request
        // JSON Schema, which sets `additionalProperties: false` and ships with the project
        // rather than with this package. Validate against it if you want typo protection.
        // See docs/SERIALIZATION.md.
        // -----------------------------------------------------------------------------------
        Map<String, Object> typo = withItem(request, 1, "keep_uprght", true);
        System.out.printf("\nmisspelled field: %s -- accepted; the misspelling is invisible to the parser,\n",
                RequestPacker.pack(typo).get("status"));
        System.out.print("                  so `keep_upright` was never applied to the mug\n");

        // An unknown *value* where the engine has to choose a behaviour is a different matter.
        // There is no sensible default for "rank by something I have never heard of".
        Map<String, Object> badObjective = with(request, "configuration",
                with((Map<?, ?>) request.get("configuration"), "objective", "cheapest"));
        try {
            RequestPacker.pack(badObjective);
        } catch (IllegalArgumentException refusal) {
            System.out.printf("unknown objective: %s\n", prefix(refusal.getMessage(), 100));
        }

        // And a field this engine names as not-yet-implemented is refused explicitly, so a
        // request written for a newer engine fails loudly instead of being half-honoured. The
        // list below is the engine's own constant, by the scope each name appears in. Its one
        // entry is `container.pallet_overhang_limit`, reserved in the schema at the 1.1.0
        // contract freeze and refused by every engine until one implements it.
        UnsupportedFields current = UnsupportedFields.CURRENT;
        Map<String, List<String>> refused = new LinkedHashMap<>();
        refused.put("request", current.request());
        refused.put("configuration", current.configuration());
        refused.put("item", current.item());
        refused.put("container", current.container());
        refused.values().removeIf(List::isEmpty);
        System.out.printf("fields this engine refuses by name: %s\n",
                refused.isEmpty() ? "none" : JsonWriter.write(refused));

        // Caught up is the right state and a poor demonstration, so the guard takes its lists
        // as parameters -- the same hook its own tests use. Passing the value retired
        // shows the refusal a caller still gets from an engine that is behind, and shows it
        // naming the *value* rather than the field: `rigid_cuboid` is the default and is
        // implemented, so a caller who spells the default out must be served, not refused.
        Map<String, Object> behind = withItem(request, 0, "shape_type", "convex_hull");
        UnsupportedFields behindOnHulls = new UnsupportedFields(
                List.of(), List.of(), List.of(), List.of(), List.of("convex_hull"));
        try {
            behindOnHulls.reject(behind);
        } catch (UnsupportedFeatureException refusal) {
            System.out.printf("  what one looks like, from an engine that is not: %s\n",
                    prefix(refusal.getMessage(), 110));
        }

        // -----------------------------------------------------------------------------------
        // The same document drives the command line, which reads a request on stdin and writes
        // a result on stdout -- which is how the cross-language conformance harness talks to
        // every engine:
        //
        //     echo '<request json>' | java -jar target/packvium-1.1.0.jar
        // -----------------------------------------------------------------------------------
        System.out.print("\nthe CLI takes exactly the document above:  echo '...' | java -jar target/packvium-1.1.0.jar\n");
    }

    private static List<Map<?, ?>> objects(Object list) {
        return ((List<?>) list).stream().<Map<?, ?>>map(element -> (Map<?, ?>) element).toList();
    }

    /** A copy of {@code object} with one member replaced; the parsed request stays untouched. */
    private static Map<String, Object> with(Map<?, ?> object, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        object.forEach((name, member) -> copy.put(name.toString(), member));
        copy.put(key, value);
        return copy;
    }

    /** A copy of the request in which item {@code index} carries one more member. */
    private static Map<String, Object> withItem(Map<?, ?> request, int index, String key, Object value) {
        List<Object> items = new ArrayList<>((List<?>) request.get("items"));
        items.set(index, with((Map<?, ?>) items.get(index), key, value));
        return with(request, "items", items);
    }

    private static String prefix(String text, int length) {
        return text.substring(0, Math.min(length, text.length()));
    }

    /** Four-space indented JSON, the layout most JSON tools print by default. */
    private static String prettyJson(Object value) {
        StringBuilder out = new StringBuilder();
        appendPretty(out, value, "");
        return out.toString();
    }

    private static void appendPretty(StringBuilder out, Object value, String indent) {
        if (value instanceof Map<?, ?> object && !object.isEmpty()) {
            String inner = indent + "    ";
            out.append("{\n");
            String separator = "";
            for (Map.Entry<?, ?> member : object.entrySet()) {
                out.append(separator).append(inner).append(JsonWriter.write(member.getKey().toString())).append(": ");
                appendPretty(out, member.getValue(), inner);
                separator = ",\n";
            }
            out.append('\n').append(indent).append('}');
        } else if (value instanceof List<?> array && !array.isEmpty()) {
            String inner = indent + "    ";
            out.append("[\n");
            String separator = "";
            for (Object element : array) {
                out.append(separator).append(inner);
                appendPretty(out, element, inner);
                separator = ",\n";
            }
            out.append('\n').append(indent).append(']');
        } else {
            out.append(JsonWriter.write(value));
        }
    }
}
