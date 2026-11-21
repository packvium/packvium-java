import com.packvium.artifacts.OperationalArtifact;
import com.packvium.revisions.PlanRevision;
import com.packvium.revisions.PlanRevisionException;
import com.packvium.revisions.RevisionIssue;
import com.packvium.serialization.RequestPacker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Replan a half-loaded job without losing what was already done, or the record of why.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.4.0.jar examples/Revisions.java
 * </pre>
 *
 * <p>A plan is approved, and the dock starts loading. Then something changes: a slab is missing
 * from the shelf, and a cube that is already in the tote must stay exactly where it is. The next
 * plan has to keep the cube in place and pack around it, and anyone auditing the job later has to
 * see what changed, in what order, and which approved plan each change was recorded against.
 *
 * <p>A plan revision is that record. It is append-only and hash-chained: each revision names its
 * parent and the artifact it replaced by SHA-256, and carries the request the events produce. The
 * replan is an ordinary solve of that request. Every Packvium engine computes the same revision
 * bytes from the same inputs.
 */
public final class Revisions {
    private static final String RULE = "=".repeat(78);

    // The edited copy is built by `mutable`, whose every object is a Map<Object, Object>.
    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) {
        Map<String, Object> request = Map.of(
                "units", Map.of("length", "mm"),
                // Counted work decides where the search stops, not a clock: a plan the clock stopped
                // could not be replayed, and its revision would say `replay: not_guaranteed`. The
                // time limit is only a fuse, far above what this needs even on a slow host.
                "configuration", Map.of("effort_budget", Map.of("max_search_nodes", 20_000L), "time_limit_ms", 60_000L),
                "items", List.of(
                        Map.of("id", "cube", "quantity", 4L, "weight", "1 kg",
                                "dimensions", Map.of("length", "100", "width", "100", "height", "100")),
                        Map.of("id", "slab", "quantity", 2L, "weight", "2 kg",
                                "dimensions", Map.of("length", "200", "width", "100", "height", "50"))),
                "containers", List.of(Map.of("id", "tote", "quantity", 2L,
                        "inner_dimensions", Map.of("length", "200", "width", "200", "height", "150"))));

        section("1. The approved plan, and the revision that records it");
        Map<String, Object> root = PlanRevision.root(request);
        Map<String, Object> approved = approve(root.get("request"));
        Object firstStep = at(approved, "plan", "containers", 0, "steps", 0, "placement");
        System.out.printf("  revision %s, parent %s\n", root.get("revision"), root.get("parent"));
        System.out.printf("  first step: a %s at x=%s in container 0\n", at(firstStep, "item_type"),
                at(firstStep, "position_ticks", "x"));
        System.out.print("\n");
        System.out.print("  The root records nothing against the request. It exists so the first change has a\n");
        System.out.print("  parent to name.\n");

        section("2. What happened on the dock, recorded against that plan");
        Object loaded = at(RequestPacker.pack(root.get("request")), "containers", 0, "placements", 0);
        Map<String, Object> position = new LinkedHashMap<>();
        for (String axis : List.of("x", "y", "z")) {
            position.put(axis, at(loaded, "position", axis, "value"));
        }
        List<Object> events = List.of(
                Map.of("sequence", 1L, "type", "placement_locked", "placement", Map.of(
                        "item_type", "cube", "container_type", "tote", "container_instance", 1L,
                        "position", position, "orientation", at(loaded, "orientation"))),
                Map.of("sequence", 2L, "type", "item_missing", "item_type", "slab", "quantity", 1L));
        Map<String, Object> revision = PlanRevision.derive(root, approved, events);
        System.out.printf("  revision %s, parent %s...\n", revision.get("revision"),
                ((String) revision.get("parent")).substring(0, 23));
        System.out.printf("  approved artifact %s..., replay %s\n",
                ((String) at(revision, "approved", "artifact")).substring(0, 23),
                at(revision, "approved", "replay", "level"));
        System.out.printf("  slabs still to pack: %s\n", at(revision, "request", "items", 1, "quantity"));
        System.out.printf("  fixed placements:    %d\n", ((List<?>) at(revision, "request", "fixed_placements")).size());
        System.out.print("\n");
        System.out.print("  The events are applied to the request, not to the result: one slab fewer, and the\n");
        System.out.print("  cube becomes a fixed placement. The revision names its parent and the artifact it\n");
        System.out.print("  replaces by SHA-256 over their RFC 8785 bytes, so every engine computes the same\n");
        System.out.print("  digest.\n");

        section("3. The replan keeps the cube where it is");
        Map<String, Object> replanned = RequestPacker.pack(revision.get("request"));
        int placed = 0;
        for (Object container : (List<?>) replanned.get("containers")) {
            for (Object placement : (List<?>) at(container, "placements")) {
                placed++;
                if (Boolean.TRUE.equals(at(placement, "fixed"))) {
                    System.out.printf("  fixed in the answer: %s in %s\n", at(placement, "item_id"), at(container, "id"));
                }
            }
        }
        System.out.printf("  items placed:        %d\n", placed);
        System.out.print("\n");
        System.out.print("  An ordinary solve of the derived request, with nothing remembered from the first\n");
        System.out.print("  one. The fixed cube is marked `fixed: true`, and the validator refuses any answer\n");
        System.out.print("  that moves it.\n");

        section("4. An audit that notices tampering, and a refusal instead of a guess");
        System.out.printf("  intact chain: %s\n",
                codes(PlanRevision.verifyChain(List.of(root, revision), Arrays.asList(null, approved))));
        Map<Object, Object> edited = mutable(revision);
        Map<Object, Object> slab = (Map<Object, Object>) at(edited, "request", "items", 1);
        slab.put("quantity", 2L);
        System.out.printf("  edited chain: %s\n", codes(PlanRevision.verifyChain(List.of(root, edited), null)));
        try {
            PlanRevision.derive(revision, approve(revision.get("request")), List.of(
                    Map.of("sequence", 3L, "type", "item_missing", "item_type", "pallet", "quantity", 1L)));
        } catch (PlanRevisionException error) {
            System.out.printf("  a pallet that was never requested: refused with %s\n", error.errorCode());
        }
        System.out.print("\n");
        System.out.print("  An edited request no longer equals what its parent's request and its own events\n");
        System.out.print("  produce, and any later revision would stop naming it as a parent. An event that\n");
        System.out.print("  contradicts the request is refused by name rather than applied as a best guess.\n");
    }

    /** Solve a request and wrap the answer as the artifact the dock works from. */
    private static Map<String, Object> approve(Object request) {
        return OperationalArtifact.build(request, RequestPacker.pack(request));
    }

    private static String codes(List<RevisionIssue> issues) {
        return issues.isEmpty() ? "no issues" : issues.stream().map(RevisionIssue::code).collect(Collectors.joining(", "));
    }

    /** A copy of a revision that can be edited, as a tampering operator would edit the stored JSON. */
    @SuppressWarnings("unchecked")
    private static <T> T mutable(Object tree) {
        if (tree instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, mutable(member)));
            return (T) copy;
        }
        if (tree instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>();
            elements.forEach(element -> copy.add(mutable(element)));
            return (T) copy;
        }
        return (T) tree;
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
