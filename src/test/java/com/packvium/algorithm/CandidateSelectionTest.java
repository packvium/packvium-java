package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Point;
import com.packvium.extension.CandidateScorer;
import com.packvium.extension.DefaultCandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.unit.Length;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The output-neutral selection changes of v1.2.0: bounded candidate selection, one-pass group
 * batching and the early-exit lattice check. Each is held to what the full computation it replaces
 * would answer, as PHP's SolverTest holds it.
 */
class CandidateSelectionTest {
    private static List<ItemInstance> instances(String id, long l, long w, long h, long quantity, String group) {
        Item.Builder builder = Item.builder(id, Dimensions.mm(l, w, h)).quantity(quantity);
        return (group == null ? builder : builder.group(group)).build().instances();
    }

    /** Records every call, and scores later origins better so a full heap must evict. */
    private static final class RecordingScorer implements CandidateScorer {
        private final List<List<Object>> calls = new ArrayList<>();

        @Override
        public SortKey score(ContainerState state, Point point, Dimensions envelope) {
            calls.add(List.of(point, envelope));
            // Negative, large integer keys and deliberate rotation ties.
            return SortKey.of(-point.x(), -Long.MAX_VALUE, envelope.height().ticks());
        }
    }

    @Test
    void boundedCandidatesPreserveFullSortTiesAndScorerCalls() {
        ContainerState state = new ContainerState(Container.builder("c", Dimensions.mm(100, 100, 100)).build(), 1);
        ItemInstance item = instances("a", 30, 20, 10, 1, null).get(0);
        List<Point> points = Stream.of(95L, 40L, 20L, 0L).map(x -> new Point(Length.mm(x).ticks(), 0, 0)).toList();
        RecordingScorer recording = new RecordingScorer();
        for (long clearance : new long[] {0, 1}) {
            PackingConfig config = PackingConfig.builder(SolverProfile.BALANCED).clearance(Length.mm(clearance)).build();
            List<PlacementConstraint> constraints = ConstraintSet.defaults(config.minimumSupportRatio());
            for (CandidateScorer scorer : List.of(new DefaultCandidateScorer(), recording)) {
                recording.calls.clear();
                SearchStats fullStats = new SearchStats();
                List<Candidate> every = CandidateFinder.find(state, item, config, constraints, fullStats,
                        Deadline.ofMilliseconds(60_000), scorer, Optional.empty(), Optional.of(points));
                List<List<Object>> calls = List.copyOf(recording.calls);
                assertTrue(every.size() > 7);
                assertTrue(new HashSet<>(every.stream().map(Candidate::score).toList()).size() < every.size(),
                        "the layout must contain tied scores");
                for (int width : new int[] {1, 2, 3, 7, 128}) {
                    recording.calls.clear();
                    SearchStats stats = new SearchStats();
                    List<Candidate> selected = CandidateFinder.find(state, item, config, constraints, stats,
                            Deadline.ofMilliseconds(60_000), scorer, Optional.of(width), Optional.of(points));
                    assertEquals(every.subList(0, Math.min(width, every.size())), selected, "width " + width);
                    assertEquals(fullStats.metrics(), stats.metrics(), "width " + width);
                    assertEquals(calls, recording.calls, "width " + width);
                }
            }
        }
    }

    @Test
    void interleavedGroupsKeepFirstOccurrenceAndMemberOrder() {
        List<ItemInstance> first = instances("a", 10, 10, 10, 2, "00");
        List<ItemInstance> second = instances("b", 10, 10, 10, 2, "0");
        List<ItemInstance> third = instances("c", 10, 10, 10, 2, "1");
        List<ItemInstance> loose = instances("loose", 10, 10, 10, 2, null);
        List<ItemInstance> ordered = List.of(second.get(1), loose.get(0), third.get(0), first.get(1), second.get(0),
                first.get(0), loose.get(1), third.get(1));
        assertEquals(List.of(List.of(second.get(1), second.get(0)), List.of(loose.get(0)), third,
                List.of(first.get(1), first.get(0)), List.of(loose.get(1))), GroupBatcher.batches(ordered));
    }

    @Test
    void latticeProfileChecksSurviveInterleavedAliasesAndReuse() {
        List<ItemInstance> first = instances("same-id", 6, 12, 20, 2, null);
        List<ItemInstance> alias = instances("alias", 12, 6, 20, 2, null);
        List<ItemInstance> different = instances("same-id", 7, 12, 20, 1, null);
        List<ItemInstance> ordered = List.of(first.get(0), alias.get(0), first.get(1), alias.get(1));
        GridSolver solver = new GridSolver();
        assertTrue(solver.supports(ordered));
        List<ItemInstance> mixed = new ArrayList<>(ordered);
        mixed.addAll(different);
        assertFalse(solver.supports(mixed));
        assertTrue(solver.supports(alias));
        assertFalse(solver.supports(List.of()));
    }
}
