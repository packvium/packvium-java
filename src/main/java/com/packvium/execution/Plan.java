package com.packvium.execution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An execution plan derived from an already validated packing result.
 *
 * <p>docs/EXECUTION-PLAN.md is the contract, and {@code packvium.execution} is the reference
 * implementation this one is held to <strong>byte-identical</strong> on {@link #canonicalJson}.
 * That bar is why several things here look more careful than they need to for Java alone:
 * integers are cast rather than trusted, keys are emitted in a fixed order, and no value is
 * ever formatted through a locale-sensitive path.
 *
 * <p>The adapter imports no solver and no validator, holds no registry and reads no clock.
 * Everything it emits is a function of the request and result it was handed -- the decoded
 * JSON values {@link com.packvium.serialization.JsonReader} yields, or the map
 * {@link com.packvium.serialization.ResultEncoder} builds -- and the plan is an immutable
 * tree of the same kind.
 *
 * <p>Two rules do most of the work, and both are about not quietly becoming a decision-maker:
 * authoritative solver facts and human-readable text are separated in the <em>output</em>
 * rather than only in the prose, and every presentation string names the fields it came from.
 * {@link #build} is O(p + u + a·s) for p placements, u unplaced items and a alternatives with
 * score vectors of length s, plus O(p log p) per injected loading order for its permutation
 * check.
 */
public final class Plan {
    /**
     * The plan's own format tag. Not the packing schema's version, and it does not move with
     * it: a result can gain fields without changing what a plan says.
     */
    public static final String FORMAT = "packvium-execution-plan/v1";

    /**
     * What a {@code score} index means is a property of the request's objective, which this
     * adapter does not know. Naming an index it cannot explain would be inventing meaning.
     */
    public static final String UNNAMED_AXIS = "unnamed objective axis";

    private static final List<String> REFERENCE_FIELDS = List.of("item_type", "orientation", "position");
    private static final List<String> AXES = List.of("x", "y", "z");
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private Plan() {
    }

    /**
     * A reference two languages agree on, for one placement in one container.
     *
     * <p>{@code containerIndex} is the container's position, never its {@code id}:
     * conformance/canonical.py drops ids from the projection two implementations are diffed
     * against, because an id is "an instance count rather than a semantic property". Position
     * is read from {@code ticks}, the exact integer, and never from {@code value}, the
     * rendering the same {@code exactScalar} also carries.
     */
    public static Map<String, Object> placementReference(int containerIndex, Map<String, ?> placement) {
        return reference(containerIndex, placement);
    }

    private static Map<String, Object> reference(int containerIndex, Map<?, ?> placement) {
        for (String required : REFERENCE_FIELDS) {
            if (!placement.containsKey(required)) {
                throw new ExecutionPlanException(
                        "placement is missing a field the reference is built from: '" + required + "'");
            }
        }
        Map<String, Object> ticks = new LinkedHashMap<>();
        for (String axis : AXES) {
            Object value = member(member(placement.get("position"), axis), "ticks");
            if (value == null) {
                throw new ExecutionPlanException(
                        "placement is missing a field the reference is built from: 'position." + axis + ".ticks'");
            }
            ticks.put(axis, integer(value));
        }
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("container_index", (long) containerIndex);
        reference.put("item_type", frozen(placement.get("item_type")));
        reference.put("orientation", frozen(placement.get("orientation")));
        reference.put("position_ticks", Collections.unmodifiableMap(ticks));
        return Collections.unmodifiableMap(reference);
    }

    /** {@link #build(Map, Map, Map)} with no engine-computed loading order for any container. */
    public static Map<String, Object> build(Map<String, ?> request, Map<String, ?> result) {
        return build(request, result, Map.of());
    }

    /**
     * Derive the execution plan for one validated result.
     *
     * @param loadingOrders container index to the engine-computed order its placements load in
     */
    public static Map<String, Object> build(Map<String, ?> request, Map<String, ?> result,
            Map<Integer, List<Integer>> loadingOrders) {
        Objects.requireNonNull(request, "request");
        if (result.get("status") == null) {
            throw new ExecutionPlanException("a result without a status is not a validated result");
        }
        List<?> containers = list(result.get("containers"), "containers");
        List<Object> winnerScore = integers(result.get("score"), "score");

        List<Object> planContainers = new ArrayList<>(containers.size());
        for (int index = 0; index < containers.size(); index++) {
            Map<?, ?> container = map(containers.get(index), "containers[]");
            List<?> placements = list(container.get("placements"), "placements");
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("container_type", frozen(container.get("container_type")));
            facts.put("placement_count", (long) placements.size());
            facts.put("volume_utilization", frozen(container.get("volume_utilization")));
            Map<String, Object> planContainer = new LinkedHashMap<>();
            planContainer.put("container_index", (long) index);
            planContainer.put("facts", Collections.unmodifiableMap(facts));
            planContainer.putAll(steps(index, placements, loadingOrders.get(index)));
            planContainers.add(Collections.unmodifiableMap(planContainer));
        }

        List<Object> unplaced = new ArrayList<>();
        for (Object entry : list(result.get("unpacked_items"), "unpacked_items")) {
            unplaced.add(unplacedEntry(map(entry, "unpacked_items[]")));
        }

        List<?> offered = list(result.get("alternatives"), "alternatives");
        List<Object> alternatives = new ArrayList<>(offered.size());
        for (int index = 0; index < offered.size(); index++) {
            alternatives.add(alternative(index, winnerScore, map(offered.get(index), "alternatives[]")));
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("status", frozen(result.get("status")));
        facts.put("score", winnerScore);
        facts.put("feasibility", frozen(result.get("feasibility")));
        facts.put("optimality", frozen(result.get("optimality")));
        facts.put("container_count", (long) containers.size());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", FORMAT);
        plan.put("objective", frozen(result.get("objective")));
        plan.put("facts", Collections.unmodifiableMap(facts));
        plan.put("containers", Collections.unmodifiableList(planContainers));
        // Often empty, and not for one reason. Four produce an empty list: the `fast` profile
        // runs a single solver, stops the start loop once the grid lattice packs
        // everything, only one start completed, or `alternatives: 1` -- the cap counts the
        // winner. An empty list is well-formed and is never an error.
        plan.put("alternatives", Collections.unmodifiableList(alternatives));
        plan.put("unplaced", Collections.unmodifiableList(unplaced));
        return Collections.unmodifiableMap(plan);
    }

    /**
     * The one byte-comparable spelling of a plan: Python's {@code json.dumps(plan,
     * sort_keys=True, separators=(",", ":"), ensure_ascii=False)}.
     *
     * <p>Cross-language equality is asserted on this string rather than on a parsed tree, so
     * key order and whitespace cannot make two identical plans look different. O(n log n) in
     * the plan's size, for the key sorts.
     */
    public static String canonicalJson(Map<String, ?> plan) {
        return CanonicalPlanJson.encode(plan);
    }

    /**
     * The operator sequence for one container, or an honest absence of one.
     *
     * <p>The engines compute a loading order from domain objects this adapter never sees, so
     * it is injected: a caller who has the order passes it, one who does not gets none.
     * Falling back to the order placements happen to appear in would present an artifact of
     * how the solver walked its candidates as a safe order to lift boxes in.
     */
    private static Map<String, Object> steps(int containerIndex, List<?> placements, List<Integer> loadingOrder) {
        List<Object> steps = new ArrayList<>(placements.size());
        if (loadingOrder == null) {
            for (Object placement : placements) {
                steps.add(Map.of("placement", reference(containerIndex, map(placement, "placements[]"))));
            }
            return orderedSteps("unavailable", steps);
        }
        if (!isPermutation(loadingOrder, placements.size())) {
            throw new ExecutionPlanException("loading order for container " + containerIndex
                    + " is not a permutation of its " + placements.size() + " placements");
        }
        for (int step = 0; step < loadingOrder.size(); step++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", (long) step + 1);
            entry.put("placement", reference(containerIndex,
                    map(placements.get(loadingOrder.get(step)), "placements[]")));
            steps.add(Collections.unmodifiableMap(entry));
        }
        return orderedSteps("loading", steps);
    }

    private static Map<String, Object> orderedSteps(String order, List<Object> steps) {
        Map<String, Object> sequence = new LinkedHashMap<>();
        sequence.put("order", order);
        sequence.put("steps", Collections.unmodifiableList(steps));
        return sequence;
    }

    private static boolean isPermutation(List<Integer> order, int size) {
        List<Integer> sorted = new ArrayList<>(order);
        sorted.sort(null);
        if (sorted.size() != size) {
            return false;
        }
        for (int index = 0; index < size; index++) {
            if (sorted.get(index) != index) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> unplacedEntry(Map<?, ?> item) {
        Object level = member(item.get("proof"), "level");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("item_type", frozen(item.get("item_type")));
        facts.put("reason", frozen(item.get("reason")));
        // Carried through unchanged. Softening `observed` into "could not fit" would turn an
        // honest limit into a false certainty.
        facts.put("proof_level", frozen(level));
        facts.put("details", frozen(list(item.get("details"), "details")));
        String summary = "Not packed: " + Objects.toString(item.get("reason"), "") + " ("
                + Objects.toString(level, "") + ").";
        return entry(facts, summary, List.of("unpacked_items[].reason", "unpacked_items[].proof.level"));
    }

    private static Map<String, Object> alternative(int index, List<Object> winnerScore, Map<?, ?> alternative) {
        List<Object> score = integers(alternative.get("score"), "alternatives[].score");
        Map<String, Object> difference = firstDifference(winnerScore, score);
        String summary = difference == null
                ? "This option scored identically to the chosen one on every objective axis; "
                        + "the score does not record why one was taken."
                : "This option differs first at objective axis " + difference.get("index") + " (" + UNNAMED_AXIS
                        + "): chosen " + difference.get("winner") + ", this " + difference.get("alternative") + ".";
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("alternative_index", (long) index);
        facts.put("score", score);
        facts.put("status", frozen(alternative.get("status")));
        facts.put("first_difference", difference);
        // Deliberately not "it lost because it is taller". The solver recorded a score, not a
        // cause; naming a cause would be a claim nothing in the result supports.
        return entry(facts, summary, List.of("score", "alternatives[].score"));
    }

    private static Map<String, Object> entry(Map<String, Object> facts, String summary, List<String> cites) {
        Map<String, Object> presentation = new LinkedHashMap<>();
        presentation.put("summary", summary);
        presentation.put("cites", cites);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("facts", Collections.unmodifiableMap(facts));
        entry.put("presentation", Collections.unmodifiableMap(presentation));
        return Collections.unmodifiableMap(entry);
    }

    /**
     * The first index at which two score vectors differ, and by how much.
     *
     * <p>Never a blended number. The portfolio compared these lexicographically, so the first
     * differing index <em>is</em> the decision; weighting the vector would replace a decision
     * that was made with one that was not.
     */
    private static Map<String, Object> firstDifference(List<Object> winner, List<Object> loser) {
        int shared = Math.min(winner.size(), loser.size());
        for (int index = 0; index < shared; index++) {
            long chosen = (Long) winner.get(index);
            long other = (Long) loser.get(index);
            if (chosen != other) {
                Map<String, Object> difference = new LinkedHashMap<>();
                difference.put("index", (long) index);
                difference.put("winner", chosen);
                difference.put("alternative", other);
                difference.put("difference", exact(BigInteger.valueOf(other).subtract(BigInteger.valueOf(chosen))));
                return Collections.unmodifiableMap(difference);
            }
        }
        if (winner.size() != loser.size()) {
            throw new ExecutionPlanException("score vectors of different length cannot be compared lexicographically");
        }
        return null;
    }

    /** A score vector with every entry cast to an integer, as the reference casts it. */
    private static List<Object> integers(Object value, String field) {
        List<Object> out = new ArrayList<>();
        for (Object element : list(value, field)) {
            out.add(integer(element));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The reference's integer cast of a decoded JSON number: a fraction truncates toward zero.
     * A value past 64 bits is refused rather than wrapped, as everywhere else in this port.
     */
    private static long integer(Object value) {
        BigInteger whole;
        if (value instanceof Long || value instanceof Integer) {
            return ((Number) value).longValue();
        } else if (value instanceof BigInteger big) {
            whole = big;
        } else if (value instanceof BigDecimal decimal) {
            whole = decimal.toBigInteger();
        } else {
            throw new ExecutionPlanException("expected an integer, got " + describe(value));
        }
        return exact(whole);
    }

    private static long exact(BigInteger value) {
        if (value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0) {
            throw new ExecutionPlanException("integer " + value + " is outside the 64-bit range");
        }
        return value.longValue();
    }

    /** A missing or null field is an empty list, as {@code ?? []} reads it. */
    private static List<?> list(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> elements) {
            return elements;
        }
        throw new ExecutionPlanException("'" + field + "' must be a list, got " + describe(value));
    }

    private static Map<?, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> members) {
            return members;
        }
        throw new ExecutionPlanException("'" + field + "' must be an object, got " + describe(value));
    }

    /** One member of an object, or null when the value is no object or lacks it, as {@code ??} reads it. */
    private static Object member(Object value, String name) {
        return value instanceof Map<?, ?> members ? members.get(name) : null;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** A deep, unmodifiable copy of a value carried through, so the plan never aliases the caller's tree. */
    private static Object frozen(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<String, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name.toString(), frozen(member)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>(elements.size());
            elements.forEach(element -> copy.add(frozen(element)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
