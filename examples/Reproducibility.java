import com.packvium.Packer;
import com.packvium.artifacts.OperationalArtifact;
import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.result.PackingResult;
import com.packvium.serialization.ResultEncoder;
import com.packvium.unit.Weight;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Get the same answer on every run: counted work instead of a clock.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.7.0.jar examples/Reproducibility.java
 * </pre>
 *
 * <p>The search tries several starts and keeps the best. Something has to decide when it stops,
 * and there are two choices:
 *
 * <ul>
 *   <li>{@code timeLimitMs} ({@code time_limit_ms} in JSON), a wall clock. Its default is one
 *       second. When it runs out, the search keeps what it has, and how far it got depends on the
 *       machine: its speed, what else is running, the garbage collector. The same request can give
 *       a different answer on a loaded server than on your laptop.
 *   <li>{@code effortBudget} ({@code effort_budget}), counted work: candidates evaluated,
 *       placements attempted, search nodes, restarts. The count is the same on every machine, so
 *       the answer is too.
 * </ul>
 *
 * <p>Set an effort budget whenever the answer is stored, compared, audited or replayed, and keep
 * the time limit as a generous fuse against a genuine hang. The result says which one stopped the
 * search, in {@code termination}.
 *
 * <p>To make a slow machine repeatable here, this example uses the injected clock the
 * {@code Packer} accepts for testing: a clock that advances a fixed amount each time it is read
 * stands in for a faster or slower host. A real deployment never passes one.
 */
public final class Reproducibility {
    private static final String RULE = "=".repeat(78);
    /** A fuse, not a limit: nothing below comes close to it on a real machine. */
    private static final long FUSE_MS = 60_000;

    private static final List<Item> ITEMS = List.of(
            item("kettle", "310", "220", "140", "2 kg", 5),
            item("toaster", "250", "250", "250", "3 kg", 4),
            item("knife-block", "400", "150", "100", "1 kg", 6),
            item("scale", "180", "120", "90", "0.5 kg", 8));
    private static final List<Container> BOXES = List.of(
            Container.builder("small", Dimensions.mm("450", "350", "300")).costMinor(100).build(),
            Container.builder("medium", Dimensions.mm("600", "400", "400")).costMinor(160).build(),
            Container.builder("large", Dimensions.mm("800", "600", "500")).costMinor(240).build());

    public static void main(String[] arguments) {
        section("1. A clock-limited search answers differently on different machines");
        // The same 100 ms limit on four simulated hosts.
        Map<String, Long> hosts = new LinkedHashMap<>();
        hosts.put("fast host", 10_000L);
        hosts.put("busy host", 50_000L);
        hosts.put("slower host", 200_000L);
        hosts.put("overloaded", 1_000_000L);
        hosts.forEach((label, step) -> System.out.printf("  %-12s %s\n", label,
                describe(solveOn(config(100, Optional.empty()), step))));
        System.out.print("""

                  Same request, four answers. Each is a valid packing of what the search reached
                  before the clock ran out, and the items it never reached are listed as left out,
                  but none can be reproduced: run it again on a different day and you get whichever
                  one the machine allows. `termination` says
                  `time_limit`, and the replay level refuses to promise a replay.
                """);

        section("2. An effort budget answers the same everywhere");
        Optional<EffortBudget> enough = Optional.of(new EffortBudget(Optional.of(5_000L), Optional.empty(),
                Optional.empty(), Optional.empty()));
        Map<String, Long> three = new LinkedHashMap<>();
        three.put("fast host", 10_000L);
        three.put("slower host", 200_000L);
        three.put("overloaded", 1_000_000L);
        three.forEach((label, step) -> System.out.printf("  %-12s %s\n", label,
                describe(solveOn(config(FUSE_MS, enough), step))));
        System.out.print("""

                  The budget is large enough that the search finishes, so `termination` is
                  `complete`, and it would stop at the same count on any machine if it were not.
                """);

        section("3. A budget that is too small is still reproducible, and says so");
        Optional<EffortBudget> tooSmall = Optional.of(new EffortBudget(Optional.of(500L), Optional.empty(),
                Optional.empty(), Optional.empty()));
        Map<String, Long> two = new LinkedHashMap<>();
        two.put("fast host", 10_000L);
        two.put("overloaded", 1_000_000L);
        two.forEach((label, step) -> System.out.printf("  %-12s %s\n", label,
                describe(solveOn(config(FUSE_MS, tooSmall), step))));
        System.out.print("""

                  The search stopped early and left items out -- `termination` is `effort_limit`,
                  not `complete` -- but it stopped at the same point on both hosts, so the answer
                  can be replayed exactly. Raise the budget until the answer is good enough, then
                  keep it fixed.
                """);

        section("4. Reading `termination`");
        Optional<EffortBudget> fourStarts = Optional.of(new EffortBudget(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(4L)));
        Map<String, Object> termination = new Packer(config(FUSE_MS, fourStarts)).pack(ITEMS, BOXES)
                .termination().toMap();
        System.out.printf("  code: %s\n", termination.get("code"));
        for (Object entry : (List<?>) termination.get("starts")) {
            Map<?, ?> start = (Map<?, ?>) entry;
            String line = "    %-28s completed=%-5s%s".formatted(start.get("id"), start.get("completed"),
                    Boolean.TRUE.equals(start.get("selected")) ? "  <- selected" : "");
            System.out.print(line.stripTrailing() + "\n");
        }
        System.out.print("""

                  `maxRestarts` caps how many portfolio starts run -- here four -- which bounds the
                  work of a large request as well as fixing it. `code` describes the selected start:
                  a losing start that was cut short leaves it `complete`, and shows up in its own
                  record and in `all_required_starts_completed` instead. Over JSON, the same budget
                  is `configuration.effort_budget`.
                """);
    }

    private static Item item(String id, String length, String width, String height, String weight, long quantity) {
        return Item.builder(id, Dimensions.mm(length, width, height)).weight(Weight.parse(weight)).quantity(quantity)
                .build();
    }

    /**
     * A monotonic clock, in nanoseconds, that moves {@code step} every time it is read: a larger
     * step is a slower or busier machine doing the same work.
     */
    private static LongSupplier host(long step) {
        long[] now = {-step};
        return () -> now[0] += step;
    }

    private static PackingConfig config(long timeLimitMs, Optional<EffortBudget> budget) {
        PackingConfig.Builder builder = PackingConfig.builder(SolverProfile.BALANCED).timeLimitMs(timeLimitMs);
        budget.ifPresent(builder::effortBudget);
        return builder.build();
    }

    private static PackingResult solveOn(PackingConfig config, long step) {
        return new Packer(config, ExtensionRegistry.none(), Optional.empty(), host(step)).pack(ITEMS, BOXES);
    }

    private static String describe(PackingResult result) {
        String opened = result.containers().stream().map(packed -> packed.container().id())
                .collect(Collectors.joining(","));
        // The replay promise an operational artifact of this result would make: exact only when no
        // clock stopped the search.
        String replay = OperationalArtifact.replayLevel(new ResultEncoder("mm", "g").encode(result));
        return "%-6s left out %2d  termination %-12s replay %s".formatted(opened, result.unpacked().size(),
                result.termination().code(), replay);
    }

    private static void section(String title) {
        System.out.printf("\n%s\n%s\n%s\n", RULE, title, RULE);
    }
}
