import com.packvium.execution.Plan;
import com.packvium.locks.LockSetError;
import com.packvium.locks.LockedResolve;
import com.packvium.locks.Locks;
import com.packvium.locks.PlacementLock;
import com.packvium.serialization.RequestPacker;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turn a packing result into instructions someone can follow on a dock.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.3.0.jar examples/Execution.java
 * </pre>
 *
 * <p>{@code pack()} answers where every box goes. That answer is not yet a work order: it does
 * not say what to lift first, it does not separate what the solver <em>decided</em> from what a
 * screen should <em>say</em>, and it has no way for the operator who is standing there to tell you
 * the printer must go in the corner.
 *
 * <p>The execution plan is that second document. It is derived from an already validated result
 * -- it calls no solver and no validator, and a test in each language asserts so. Anything it
 * could decide on its own would be a decision made twice.
 *
 * <p>The scene below is the same one the PHP and Python examples build, and the plan is held to
 * a byte-identical canonical form across the engines. Sections 5 and 6 follow the Python example
 * into operator locks.
 */
public final class Execution {
    private static final String RULE = "=".repeat(78);

    public static void main(String[] arguments) {
        // One crate, a printer that must stay upright, four toner cartridges, and a pallet jack
        // that was never going to fit. The last one is deliberate: an execution plan has to say
        // what is *not* going on the truck as clearly as what is.
        Map<String, Object> request = Map.of(
                "units", Map.of("length", "mm"),
                // `time_limit_ms` is a safety fuse, not a target -- nothing in this scene comes
                // close to it.
                "configuration", Map.of("objective", "default", "profile", "balanced", "seed", 42L,
                        "time_limit_ms", 60_000L),
                "items", List.of(
                        Map.of("id", "printer", "quantity", 1L, "weight", "9 kg", "keep_upright", true,
                                "dimensions", Map.of("length", "420", "width", "340", "height", "260")),
                        Map.of("id", "toner", "quantity", 4L, "weight", "900 g",
                                "dimensions", Map.of("length", "180", "width", "120", "height", "100")),
                        Map.of("id", "pallet-jack", "quantity", 1L, "weight", "80 kg",
                                "dimensions", Map.of("length", "1200", "width", "550", "height", "1200"))),
                "containers", List.of(
                        Map.of("id", "crate", "quantity", 1L, "max_payload", "30 kg",
                                "inner_dimensions", Map.of("length", "600", "width", "400", "height", "400"))));

        Map<String, Object> result = RequestPacker.pack(request);
        Map<String, Object> plan = Plan.build(request, result);
        Map<?, ?> container = (Map<?, ?>) ((List<?>) plan.get("containers")).get(0);

        section("1. What the solver decided, kept apart from what a screen says");
        Map<?, ?> facts = (Map<?, ?>) plan.get("facts");
        System.out.printf("  format:          %s\n", plan.get("format"));
        System.out.printf("  status:          %s\n", facts.get("status"));
        System.out.printf("  containers used: %d\n", facts.get("container_count"));
        System.out.printf("  score:           [%s]\n", joined((List<?>) facts.get("score")));
        System.out.printf("  utilization:     %s\n", ((Map<?, ?>) container.get("facts")).get("volume_utilization"));
        System.out.print("\n");
        System.out.print("  Everything above is under `facts`. It is the solver's own answer, copied and\n");
        System.out.print("  not re-derived, so a downstream system that reads only `facts` loses nothing it\n");
        System.out.print("  is entitled to rely on. The score stays a vector: collapsing five axes into one\n");
        System.out.print("  number is a judgement about your priorities that this document does not make.\n");
        System.out.print("\n");

        section("2. The step order is injected, or it is honestly absent");
        System.out.printf("  order: %s\n", container.get("order"));
        List<?> steps = (List<?>) container.get("steps");
        for (Object step : steps) {
            Map<?, ?> reference = (Map<?, ?>) ((Map<?, ?>) step).get("placement");
            Map<?, ?> ticks = (Map<?, ?>) reference.get("position_ticks");
            System.out.printf("    %-9s %s  at (%d, %d, %d)\n", reference.get("item_type"),
                    reference.get("orientation"), ticks.get("x"), ticks.get("y"), ticks.get("z"));
        }
        System.out.print("\n");
        System.out.print("  Every placement is listed and not one is numbered. The engines compute a safe\n");
        System.out.print("  loading order from geometry this adapter never sees, so without one it says\n");
        System.out.print("  `unavailable` rather than guessing.\n");
        System.out.print("\n");
        System.out.print("  There is no third behaviour on purpose. Falling back to the order placements\n");
        System.out.print("  happen to appear in would present an artifact of how the solver walked its\n");
        System.out.print("  candidate points as an order that is safe to lift boxes in. It is not.\n");
        System.out.print("\n");

        List<Integer> reversed = new ArrayList<>();
        for (int index = steps.size() - 1; index >= 0; index--) {
            reversed.add(index);
        }
        Map<String, Object> ordered = Plan.build(request, result, Map.of(0, reversed));
        Map<?, ?> orderedContainer = (Map<?, ?>) ((List<?>) ordered.get("containers")).get(0);
        System.out.printf("  order: %s\n", orderedContainer.get("order"));
        for (Object step : (List<?>) orderedContainer.get("steps")) {
            Map<?, ?> entry = (Map<?, ?>) step;
            System.out.printf("    %d. %s\n", entry.get("sequence"), ((Map<?, ?>) entry.get("placement")).get("item_type"));
        }
        System.out.print("\n");
        System.out.print("  Hand it an order and each step is numbered. The order above is reversed on\n");
        System.out.print("  purpose, to show that the sequence is the one you supplied and not one the\n");
        System.out.print("  adapter re-derived behind your back.\n");
        System.out.print("\n");

        section("3. Every sentence names the fields it was built from");
        for (Object unplaced : (List<?>) plan.get("unplaced")) {
            Map<?, ?> entryFacts = (Map<?, ?>) ((Map<?, ?>) unplaced).get("facts");
            Map<?, ?> presentation = (Map<?, ?>) ((Map<?, ?>) unplaced).get("presentation");
            System.out.printf("  facts:        item_type='%s'\n", entryFacts.get("item_type"));
            System.out.printf("                reason='%s' proof_level='%s'\n", entryFacts.get("reason"),
                    entryFacts.get("proof_level"));
            System.out.printf("  presentation: %s\n", presentation.get("summary"));
            System.out.printf("  cites:        %s\n", joined((List<?>) presentation.get("cites")));
        }
        System.out.print("\n");
        System.out.print("  `proven` is a claim about a search, not a summary of one: no orientation of the\n");
        System.out.print("  pallet jack fits any offered crate, so nothing was tried and nothing needed to\n");
        System.out.print("  be. A reason with no citation would be a sentence nobody can check, which is\n");
        System.out.print("  why `cites` is part of the format rather than a convention.\n");
        System.out.print("\n");

        section("4. One plan, every engine, the same bytes");
        String canonical = Plan.canonicalJson(plan);
        System.out.printf("  canonical form: %d bytes, first 68 of them\n",
                canonical.getBytes(StandardCharsets.UTF_8).length);
        System.out.printf("    %s...\n", canonical.substring(0, 68));
        System.out.print("\n");
        System.out.print("  Hand the same *result* to any Packvium adapter, this one included, and it emits\n");
        System.out.print("  the same bytes. That is stricter than the packing contract, and it can be: a\n");
        System.out.print("  plan is derived from a result, so there is nothing left to differ about.\n");
        System.out.print("\n");
        System.out.print("  It does not follow that every engine packing the same *request* agrees. This\n");
        System.out.print("  engine, like Python and PHP, is held to identical placements and produces this\n");
        System.out.print("  exact form; the Rust and JavaScript engines are held to a valid answer at or\n");
        System.out.print("  above the objective floor, so their plan for this request can be a different\n");
        System.out.print("  length. Whose packing is better is a question the plan never answers.\n");
        System.out.print("\n");

        section("5. The operator pins a box, and gets a second plan — never an edited one");
        List<PlacementLock> locks = Locks.locksFromPlan(plan, 0, List.of("printer"));
        PlacementLock lock = locks.get(0);
        System.out.printf("  locked: %s %s at %s\n", lock.itemType(), lock.orientation(), tuple(lock.positionTicks()));
        System.out.print("  The lock is read out of the plan's own placement reference. That is the only\n");
        System.out.print("  reason the two can be trusted to mean the same box: there is no second address\n");
        System.out.print("  format to drift.\n");
        System.out.print("\n");

        LockedResolve resolved = Locks.resolveWithLocks(request, locks);
        System.out.printf("  preserved: %s\n", resolved.preserved());
        System.out.printf("  the approved plan is untouched: %s\n",
                Plan.canonicalJson(Plan.build(request, result)).equals(canonical));
        System.out.print("\n");
        System.out.print("  A lock becomes an ordinary placement constraint, so the re-solve is the same\n");
        System.out.print("  portfolio under the same independent validator. Nothing here can produce a\n");
        System.out.print("  placement the engine would otherwise refuse — the strongest thing a lock can do\n");
        System.out.print("  is reserve its own slot and make the search work around it.\n");
        System.out.print("\n");

        section("6. Two ways a lock can fail, and they are not the same kind of thing");
        // Far outside the crate: a single lock that is individually impossible. The solve still
        // runs and still answers; it simply cannot honour this one.
        PlacementLock unreachable = new PlacementLock(0, "printer", "LWH", 99_000_000, 0, 0);
        LockedResolve outcome = Locks.resolveWithLocks(request, List.of(unreachable));
        System.out.printf("  a lock the solve cannot honour -> preserved=%s, %d lock reported back\n",
                outcome.preserved(), outcome.missing().size());
        System.out.printf("    %s at %s\n", outcome.missing().get(0).itemType(),
                tuple(outcome.missing().get(0).positionTicks()));
        System.out.print("  Not an exception. The operator asked for something the geometry does not allow,\n");
        System.out.print("  and what they need back is a valid plan plus the news that their pin was not\n");
        System.out.print("  honoured — not a stack trace and no plan at all.\n");
        System.out.print("\n");

        // An item the request never contained: the lock set is wrong about the world, and no solve
        // could make it right.
        try {
            Locks.resolveWithLocks(request, List.of(lock, new PlacementLock(0, "scanner", "LWH", 0, 0, 0)));
        } catch (LockSetError refusal) {
            System.out.printf("  a lock set that contradicts itself -> %s\n", refusal.getMessage());
        }
        System.out.print("\n");
        System.out.print("  Refused before any solve runs, because no result could satisfy it. The same goes\n");
        System.out.print("  for two locks whose boxes overlap. The distinction is worth keeping: the first\n");
        System.out.print("  case is an answer the operator may not like, the second is a question that has\n");
        System.out.print("  no answer, and reporting them the same way would hide which one happened.\n");
    }

    private static void section(String title) {
        System.out.print(RULE + "\n" + title + "\n" + RULE + "\n\n");
    }

    private static String tuple(List<Long> ticks) {
        return "(" + joined(ticks) + ")";
    }

    private static String joined(List<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
