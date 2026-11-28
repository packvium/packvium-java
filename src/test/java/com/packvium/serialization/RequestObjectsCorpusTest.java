package com.packvium.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.Item;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.unit.Length;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Every malformed request of the shared request-error corpus is refused on the object path with
 * the refusal the JSON path gives.
 *
 * <p>Each case is built as objects with {@link RequestDecoder}'s own builders, but without the
 * JSON rules in front of them, and passed to {@link Packer#pack}. A refusal while an object is
 * built must carry the JSON path's exception class, code and reason with an empty field; a refusal
 * from {@code pack} must carry its field and message too. A case with no object form, or whose
 * difference is kept on purpose, is listed below with why; a listed case that starts matching
 * fails, so it has to leave the list. Runs when {@code PACKVIUM_TAG_ROOT} names a checkout of the
 * Packvium suite repository at the tag this release replays.
 */
class RequestObjectsCorpusTest {
    private static final String UNKNOWN_KEY = "an object has no place for a key its class does not declare";
    private static final String COERCED = "the decoder converts it, so the objects it builds are a different, "
            + "valid request";
    private static final String OUTPUT = "the output units are arguments of the result encoder, not part of the "
            + "objects packed";
    private static final String CONSTRUCTOR_FIRST = "two faults: a constructor refuses its own value as the object "
            + "is built, before Packer.pack walks the request in the JSON order";
    private static final String NEGATIVE_POSITION = "a Point cannot hold a negative coordinate; the measure it is "
            + "read with refuses it as negative_measure, where the JSON path names the placement";
    private static final String ENUM = "the object holds an enum constant, which cannot hold a stranger";

    /** Cases with no object form: the value cannot be held, or the decoder changes it. */
    private static final Map<String, String> NOT_OBJECTS = notObjects();

    /** Cases the object path answers differently on purpose. */
    private static final Map<String, String> DIFFERENT_ON_PURPOSE = Map.of(
            "configuration-objective-unknown", "an unknown objective keeps UnknownObjectiveException",
            "configuration-solver-unknown", "an unknown solver keeps UnknownSolverException",
            "container-access-direction-unknown", "an unknown direction keeps its own refusal",
            "item-accepts-stacking-groups-entry-empty", "a set's index counts in code-point order",
            "an-item-id-before-its-quantity", CONSTRUCTOR_FIRST,
            "item-ids-before-containers", CONSTRUCTOR_FIRST,
            "container-ids-before-fixed-placements", CONSTRUCTOR_FIRST);

    /** Pinned, so the skip set cannot grow without this line changing too. */
    private static final int SKIPPED = 60;

    private static Map<String, String> notObjects() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("request-not-object", "a request that is not an object has no objects");
        cases.put("items-missing", "Packer.pack takes the items as an argument");
        cases.put("items-not-list", "Packer.pack takes the items as a list of Item");
        cases.put("item-not-object", "an item that is not an object is not an Item");
        cases.put("containers-missing", "Packer.pack takes the containers as an argument");
        cases.put("configuration-not-object", "a configuration that is not an object is not a PackingConfig");
        cases.put("effort-not-object", "an effort budget that is not an object is not an EffortBudget");
        cases.put("container-made-to-measure-not-object", "a machine that is not an object is not a MadeToMeasure");
        cases.put("container-deformable-boundary-not-object", "the object holds the bag's kind, not a block");
        cases.put("container-deformable-boundary-kind-missing", "the object holds the bag's kind, not a block");
        cases.put("item-id-missing", "Item requires an id");
        cases.put("item-dimensions-missing", "Item requires dimensions");
        cases.put("item-side-missing", "Dimensions requires all three sides");
        cases.put("container-made-to-measure-minimum-missing", "MadeToMeasure requires a minimum");
        cases.put("container-made-to-measure-increment-missing", "MadeToMeasure requires an increment");
        cases.put("rate-brackets-not-list", "RateTable holds its brackets as a list");
        cases.put("solver-profile-unknown", "SolverProfile has no such constant");
        cases.put("configuration-key-unknown-before-bad-value", "SolverProfile has no such constant, and " + UNKNOWN_KEY);
        for (String name : List.of("configuration-key-unknown", "configuration-key-unknown-null",
                "configuration-key-unknown-escaped", "configuration-keys-unknown-first-in-code-point-order",
                "effort-key-unknown", "container-deformable-boundary-key-unknown",
                "container-made-to-measure-key-unknown", "unknown-configuration-key-before-its-values")) {
            cases.put(name, UNKNOWN_KEY);
        }
        cases.put("unsupported-before-units", "an unsupported field has no object form");
        cases.put("unsupported-before-an-item", "an unsupported field has no object form");
        cases.put("container-flat-bag-with-access-directions", "an empty list is how an object leaves the field out");
        for (String name : List.of("item-id-number", "item-quantity-text", "item-accepts-stacking-groups-not-list",
                "container-access-directions-not-list", "effort-limit-text", "item-stacking-group-number",
                "item-accepts-stacking-groups-entry-number")) {
            cases.put(name, COERCED);
        }
        cases.put("fixed-position-unparsable", "a Point holds integer ticks, not text");
        for (String axis : List.of("x", "y", "z")) {
            cases.put("fixed_placements.position." + axis + "=\"-1\"", NEGATIVE_POSITION);
            cases.put("fixed_placements.position." + axis + "=-1", NEGATIVE_POSITION);
        }
        for (String name : List.of("item-allowed-rotations-not-list", "item-allowed-rotation-unknown",
                "item-allowed-rotation-lowercase", "item-shape-type-unknown", "fixed-orientation-unknown",
                "configuration-solvers-not-list")) {
            cases.put(name, ENUM);
        }
        for (String name : List.of("output-not-object", "output-length-unit-unknown", "output-weight-unit-unknown",
                "output-before-configuration")) {
            cases.put(name, OUTPUT);
        }
        return cases;
    }

    @Test
    void theSkipSetIsPinnedAndNamesOnlyCorpusCases() throws IOException {
        Map<String, Object> corpus = corpus();
        Map<String, String> skips = skips();
        assertTrue(NOT_OBJECTS.keySet().stream().noneMatch(DIFFERENT_ON_PURPOSE::containsKey));
        assertTrue(corpus.keySet().containsAll(skips.keySet()), "a skip names no corpus case");
        assertEquals(SKIPPED, skips.size());
        assertTrue(corpus.size() - SKIPPED > 100, "the corpus quietly emptied itself");
    }

    @Test
    void theObjectPathRefusesAsTheJsonPathDoes() throws IOException {
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, Object> entry : corpus().entrySet()) {
            if (skips().containsKey(entry.getKey())) {
                continue;
            }
            Outcome outcome = outcome(entry.getValue());
            if (!outcome.same()) {
                differences.add(entry.getKey() + ": " + outcome);
            }
        }
        assertEquals(List.of(), differences);
    }

    @Test
    void aSkippedCaseStillDiffers() throws IOException {
        List<String> matching = new ArrayList<>();
        for (Map.Entry<String, Object> entry : corpus().entrySet()) {
            if (skips().containsKey(entry.getKey()) && outcome(entry.getValue()).same()) {
                matching.add(entry.getKey());
            }
        }
        assertEquals(List.of(), matching, "a skip that matches has been fixed, and must leave its list");
    }

    private static Map<String, String> skips() {
        Map<String, String> skips = new HashMap<>(NOT_OBJECTS);
        skips.putAll(DIFFERENT_ON_PURPOSE);
        return skips;
    }

    private static Map<String, Object> corpus() throws IOException {
        String root = System.getenv("PACKVIUM_TAG_ROOT");
        assumeTrue(root != null, "PACKVIUM_TAG_ROOT not set");
        Path path = Path.of(root, "conformance", "request_errors", "corpus.json");
        assumeTrue(Files.exists(path), "no request-error corpus at the tag");
        Map<?, ?> document = (Map<?, ?>) JsonReader.parse(Files.readString(path, StandardCharsets.UTF_8));
        assertEquals("packvium-request-error-corpus/v1", document.get("format"));
        Map<String, Object> cases = new TreeMap<>();
        for (Object raw : (List<?>) document.get("cases")) {
            Map<?, ?> entry = (Map<?, ?>) raw;
            cases.put((String) entry.get("name"), entry.get("request"));
        }
        return cases;
    }

    private static Outcome outcome(Object request) {
        RuntimeException json = null;
        try {
            RequestPacker.pack(request);
        } catch (RuntimeException error) {
            json = error;
        }
        Objects.requireNonNull(json, "the JSON path answered a malformed request");
        Built built;
        try {
            built = objects(request);
        } catch (RuntimeException error) {
            return new Outcome(json, error, "constructor");
        }
        try {
            new Packer(built.config()).pack(built.items(), built.containers(), built.fixed());
        } catch (RuntimeException error) {
            return new Outcome(json, error, "pack");
        }
        return new Outcome(json, null, "pack");
    }

    /** The request as objects, built by the decoder's own builders without the rules before them. */
    private static Built objects(Object request) {
        Fields data = new Fields(request, "request");
        String unit = data.child("units").text("length", "mm");
        PackingConfig config = (PackingConfig) builder("config", data.child("configuration"), unit);
        List<Item> items = new ArrayList<>();
        for (Object entry : (List<?>) data.required("items")) {
            items.add((Item) builder("item", new Fields(entry, "item"), unit));
        }
        List<Container> containers = new ArrayList<>();
        for (Object entry : (List<?>) data.required("containers")) {
            containers.add((Container) builder("container", new Fields(entry, "container"), unit));
        }
        List<FixedPlacement> fixed = new ArrayList<>();
        for (Object entry : data.list("fixed_placements")) {
            fixed.add(fixedPlacement(new Fields(entry, "fixed_placement"), unit));
        }
        return new Built(config, items, containers, fixed);
    }

    /** The decoder's placement reader without the shape rules in front of it. */
    private static FixedPlacement fixedPlacement(Fields entry, String unit) {
        Fields position = entry.child("position");
        Point origin = new Point(coordinate(position, "x", unit), coordinate(position, "y", unit),
                coordinate(position, "z", unit));
        Object instance = entry.raw("container_instance");
        return new FixedPlacement((String) entry.raw("item_type"), (String) entry.raw("container_type"), origin,
                Rotation.valueOf(String.valueOf(entry.raw("orientation"))),
                instance == null ? 1 : ((Number) instance).longValue());
    }

    private static long coordinate(Fields position, String axis, String unit) {
        return Length.parse(position.has(axis) ? position.raw(axis) : 0L, unit).ticks();
    }

    private static Object builder(String name, Fields fields, String unit) {
        try {
            Method method = RequestDecoder.class.getDeclaredMethod(name, Fields.class, String.class);
            method.setAccessible(true);
            return method.invoke(null, fields, unit);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException error) {
                throw error;
            }
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Built(PackingConfig config, List<Item> items, List<Container> containers,
            List<FixedPlacement> fixed) {
    }

    private record Outcome(RuntimeException json, RuntimeException objects, String stage) {
        boolean same() {
            if (!(objects instanceof InvalidRequestException error) || !(json instanceof InvalidRequestException wire)
                    || !wire.getClass().isInstance(error)) {
                return false;
            }
            if (!error.errorCode().equals(wire.errorCode()) || !error.reason().equals(wire.reason())) {
                return false;
            }
            if (stage.equals("constructor")) {
                return error.field().isEmpty();
            }
            return error.field().equals(wire.field()) && error.getMessage().equals(wire.getMessage());
        }

        @Override
        public String toString() {
            return "json " + describe(json) + "; objects at " + stage + " " + describe(objects);
        }

        private static String describe(RuntimeException error) {
            if (error == null) {
                return "accepted";
            }
            if (error instanceof InvalidRequestException refusal) {
                return error.getClass().getSimpleName() + "(" + refusal.errorCode() + ", " + refusal.reason() + ", "
                        + refusal.field() + ", " + error.getMessage() + ")";
            }
            return error.getClass().getSimpleName() + "(" + error.getMessage() + ")";
        }
    }
}
