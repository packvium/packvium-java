import com.packvium.serialization.InvalidRequestException;
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.JsonWriter;
import com.packvium.serialization.RequestPacker;
import com.packvium.validation.FixedPlacementException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turn a refused request into a useful answer, the way a web handler would.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.6.0.jar examples/Errors.java
 * </pre>
 *
 * <p>A packing service takes JSON from a caller it does not control. Most failures are the
 * caller's: a quantity of zero, a negative width, a unit nobody has heard of. Those throw
 * {@code InvalidRequestException} before anything is solved -- an {@code IllegalArgumentException},
 * so existing handlers still catch it -- with three things a program can use:
 *
 * <ul>
 *   <li>{@code reason()}: one of a closed set ({@code missing_field}, {@code wrong_type},
 *       {@code below_minimum}, ...), the thing to branch on;
 *   <li>{@code field()}: a JSON Pointer to the bad value in the request the caller sent, such as
 *       {@code /items/0/quantity};
 *   <li>{@code getMessage()}: {@code invalid_request: <field>: <detail>}, the same text in every
 *       Packvium engine, fit for a log line.
 * </ul>
 *
 * <p>{@code FixedPlacementException} is a subclass for items already in place that cannot be where
 * the request says. And a request that is valid but does not fit completely is not an error at
 * all: it is a result with {@code unpacked_items}.
 */
public final class Errors {
    private static final String RULE = "=".repeat(78);

    private static final Map<String, Object> ORDER = object(
            "units", object("length", "mm"),
            "configuration", object("time_limit_ms", 60_000L, "effort_budget", object("max_restarts", 4L)),
            "items", List.of(
                    object("id", "mug", "quantity", 2L, "weight", "380 g",
                            "dimensions", object("length", "100", "width", "100", "height", "120")),
                    object("id", "teapot", "quantity", 1L, "weight", "1.2 kg",
                            "dimensions", object("length", "220", "width", "160", "height", "180"))),
            "containers", List.of(object("id", "box",
                    "inner_dimensions", object("length", "300", "width", "250", "height", "200"))));

    public static void main(String[] arguments) {
        section("1. Refused before solving: one reason, one pointer, one message");
        show("a valid order", ORDER);
        show("quantity 0", broken(request -> item(request, 0).put("quantity", 0L)));
        show("a negative width", broken(request -> dimensions(request, 1).put("width", "-160")));
        show("a missing height", broken(request -> dimensions(request, 1).remove("height")));
        show("a float where a measure belongs", broken(request -> dimensions(request, 1).put("width", 160.5)));
        show("the same container id twice", broken(request -> list(request, "containers")
                .add(copy(list(request, "containers").get(0)))));
        show("an unknown solver profile", broken(request -> configuration(request).put("solver_profile", "turbo")));
        System.out.print("""

                  A float is refused as a measure on purpose: binary floating point cannot hold most
                  decimal lengths exactly, and every length here is exact. Send "160.5" as a string.
                  When several values are wrong, the first is reported, in a fixed order every
                  engine shares: units, configuration, items, containers, fixed placements.
                """);

        section("2. Items already in place that cannot be there");
        // Turned (`WLH`: its width along the box's length) so the mugs still fit beside it.
        Map<String, Object> onBoard = object("item_type", "teapot", "container_type", "box", "orientation", "WLH",
                "position", object("x", "0", "y", "0", "z", "0"));
        show("a teapot already in the box, packed around",
                broken(request -> request.put("fixed_placements", List.of(onBoard))));
        show("a teapot recorded at x = 200 mm, hanging out of a 300 mm box",
                broken(request -> request.put("fixed_placements",
                        List.of(with(onBoard, "position", object("x", "200"))))));
        show("a mug and the teapot recorded in the same place",
                broken(request -> request.put("fixed_placements", List.of(onBoard,
                        object("item_type", "mug", "container_type", "box", "orientation", "LWH")))));
        show("an orientation code that does not exist",
                broken(request -> request.put("fixed_placements", List.of(with(onBoard, "orientation", "XYZ")))));
        System.out.print("""

                  `cannot_hold` means the fixed set is well formed but is not a valid packing on its
                  own, and `field` is the whole list. `malformed` points at the one bad value.
                """);

        section("3. Not an error: a valid order that does not fit");
        show("a teapot too tall for any box", broken(request -> dimensions(request, 1).put("height", "450")));
        System.out.print("""

                  The caller asked a fair question and got an answer: the rest of the order is
                  packed, and `unpacked_items` says what is not and why. Retrying it will not help,
                  and neither will treating it as a 4xx.
                """);

        section("4. Two refusals that do not carry a pointer");
        Map<String, Consumer<Map<String, Object>>> changes = new LinkedHashMap<>();
        changes.put("an unknown objective", request -> configuration(request).put("objective", "cheapest"));
        changes.put("an unknown door",
                request -> list(request, "containers").set(0, with(list(request, "containers").get(0),
                        "access_directions", List.of("rear"))));
        changes.forEach((label, change) -> {
            try {
                RequestPacker.pack(broken(change));
            } catch (InvalidRequestException error) {
                System.out.printf("  %s: InvalidRequestException, reason %s, field \"%s\": %s\n", label,
                        error.reason(), error.field(), prefix(error.getMessage(), 50));
            } catch (IllegalArgumentException error) {
                System.out.printf("  %s: %s: %s\n", label, error.getClass().getSimpleName(),
                        prefix(error.getMessage(), 70));
            }
        });
        System.out.print("""

                  Both are `IllegalArgumentException`s, so a handler that ends with
                  `catch (IllegalArgumentException e)` still turns them into a 422 -- but neither
                  has a field to highlight. The objective is refused by the objective registry with
                  a message only; the door reaches the request error with reason `invalid_value` and
                  an empty pointer, which means the request as a whole.
                """);
    }

    /** What a POST /pack endpoint would do: a status code and a JSON body. */
    private static Response handle(String body) {
        Map<?, ?> request = (Map<?, ?>) JsonReader.parse(body);
        Map<String, Object> result;
        try {
            result = RequestPacker.pack(request);
        } catch (FixedPlacementException error) {
            // A subclass of InvalidRequestException, so it must be caught first. Its detail names
            // the physical problem (a collision, too much weight), which is what a dock operator
            // needs to hear.
            return new Response(422, object("code", error.errorCode(), "reason", error.reason(), "field",
                    error.field(), "message",
                    "The items already loaded cannot stay as recorded: " + error.detail() + "."));
        } catch (InvalidRequestException error) {
            return new Response(422, object("code", error.errorCode(), "reason", error.reason(), "field",
                    error.field(), "message",
                    "Please check " + where(request, error.field()) + ": " + error.detail() + "."));
        }
        // Not an error: the caller gets the plan, and a clear list of what is not in it.
        List<Object> leftOut = new ArrayList<>();
        ((List<?>) result.get("unpacked_items")).forEach(item -> leftOut.add(((Map<?, ?>) item).get("item_id")));
        return new Response(200, object("status", result.get("status"), "boxes",
                (long) ((List<?>) result.get("containers")).size(), "left_out", leftOut));
    }

    /** Every value along a JSON Pointer, root first, so a message can name the item by id. */
    private static List<Object> resolve(Object document, String pointer) {
        List<Object> values = new ArrayList<>(List.of(document));
        for (String token : tokens(pointer)) {
            String key = token.replace("~1", "/").replace("~0", "~");
            Object current = values.get(values.size() - 1);
            Object next;
            if (current instanceof Map<?, ?> object && object.containsKey(key)) {
                next = object.get(key);
            } else if (current instanceof List<?> array && key.matches("\\d+")
                    && Integer.parseInt(key) < array.size()) {
                next = array.get(Integer.parseInt(key));
            } else {
                break;
            }
            values.add(next);
        }
        return values;
    }

    /**
     * {@code /items/1/dimensions/width} becomes {@code item "teapot", dimensions/width}.
     *
     * <p>The pointer is exact but written for programs. People know their items by id, so the
     * nearest object that has one names the place, and the rest of the pointer says which value
     * in it.
     */
    private static String where(Map<?, ?> request, String pointer) {
        List<String> tokens = tokens(pointer);
        List<Object> values = resolve(request, pointer);
        for (int depth = values.size() - 1; depth > 0; depth--) {
            if (values.get(depth) instanceof Map<?, ?> value && value.get("id") != null) {
                String kind = tokens.get(depth - 2).replaceAll("s+$", "");
                String rest = String.join("/", tokens.subList(depth, tokens.size()));
                return "%s \"%s\"".formatted(kind, value.get("id")) + (rest.isEmpty() ? "" : ", " + rest);
            }
        }
        return pointer.isEmpty() ? "the request" : pointer;
    }

    private static List<String> tokens(String pointer) {
        List<String> parts = Arrays.asList(pointer.split("/", -1));
        return parts.subList(Math.min(1, parts.size()), parts.size());
    }

    /** JSON with a space after each separator, the way the other Packvium examples print it. */
    private static String spaced(Object value) {
        if (value instanceof List<?> array) {
            List<String> parts = new ArrayList<>();
            array.forEach(element -> parts.add(spaced(element)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof Map<?, ?> object) {
            List<String> pairs = new ArrayList<>();
            object.forEach((key, entry) -> pairs.add(spaced(key.toString()) + ": " + spaced(entry)));
            return "{" + String.join(", ", pairs) + "}";
        }
        return JsonWriter.write(value);
    }

    private static void show(String label, Map<String, Object> request) {
        Response response = handle(JsonWriter.write(request));
        System.out.printf("  %s\n", label);
        System.out.printf("    HTTP %d  %s\n", response.status(), spaced(response.body()));
    }

    /** A copy of the order with {@code change} applied to it. */
    private static Map<String, Object> broken(Consumer<Map<String, Object>> change) {
        Map<String, Object> request = copy(ORDER);
        change.accept(request);
        return request;
    }

    private static void section(String title) {
        System.out.printf("\n%s\n%s\n%s\n", RULE, title, RULE);
    }

    // -------------------------------------------------------------------- document helpers

    /** An ordered JSON object from alternating keys and values. */
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], pairs[index + 1]);
        }
        return out;
    }

    /** A deep, mutable copy, so an edit never reaches the order every case starts from. */
    @SuppressWarnings("unchecked")
    private static <T> T copy(T value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> out = new LinkedHashMap<>();
            source.forEach((key, entry) -> out.put(key.toString(), copy(entry)));
            return (T) out;
        }
        if (value instanceof List<?> source) {
            List<Object> out = new ArrayList<>();
            source.forEach(entry -> out.add(copy(entry)));
            return (T) out;
        }
        return value;
    }

    private static Map<String, Object> with(Object object, String key, Object value) {
        Map<String, Object> out = copy(map(object));
        out.put(key, value);
        return out;
    }

    // Every object the helpers reach was built by `object` or `copy`, so each is a
    // Map<String, Object> and each list a List<Object>.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> request, String key) {
        return (List<Object>) request.get(key);
    }

    private static Map<String, Object> item(Map<String, Object> request, int index) {
        return map(list(request, "items").get(index));
    }

    private static Map<String, Object> dimensions(Map<String, Object> request, int index) {
        return map(item(request, index).get("dimensions"));
    }

    private static Map<String, Object> configuration(Map<String, Object> request) {
        return map(request.get("configuration"));
    }

    private static String prefix(String text, int length) {
        return text.substring(0, Math.min(length, text.length()));
    }

    private record Response(int status, Map<String, Object> body) {
    }
}
