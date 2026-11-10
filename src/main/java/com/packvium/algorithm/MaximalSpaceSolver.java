package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Obstacle;
import com.packvium.domain.Point;
import com.packvium.extension.CandidateScorer;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maximal free spaces plus the common feasibility engine.
 *
 * <p>Candidates are evaluated at each space origin: asking the finder for its globally best
 * point and testing whether it equals a space origin would reject almost every space, so the
 * point set is passed explicitly.
 */
public final class MaximalSpaceSolver implements SingleContainerSolver {
    /**
     * Past this many surviving spaces the smallest are dropped first. Adversarial mixes carve
     * roughly linearly more spaces per placement once size variety defeats the dominance filter,
     * and every extra one multiplies the choose scan and the O(new * s) dominance check.
     */
    public static final int MAX_MAXIMAL_SPACES = 256;

    private final List<PlacementConstraint> constraints;
    private final CandidateScorer scorer;

    public MaximalSpaceSolver(List<PlacementConstraint> constraints, CandidateScorer scorer) {
        this.constraints = List.copyOf(constraints);
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return "maximal_spaces";
    }

    @Override
    public SingleContainerSolution packOne(Container container, long sequence, List<ItemInstance> items,
            PackingConfig config, SearchStats stats, Deadline deadline) {
        List<PlacementConstraint> active = ConstraintSet.defaults(config.minimumSupportRatio(), constraints, List.of());
        ContainerState state = new ContainerState(container, sequence);
        List<AxisAlignedBox> obstacleBoxes = new ArrayList<>();
        for (Obstacle obstacle : container.obstacles()) {
            obstacleBoxes.addAll(obstacle.boxes());
        }
        List<Space> spaces = subtractAll(List.of(new Space(Point.ORIGIN, container.innerDimensions())), obstacleBoxes,
                Optional.of(stats));
        List<ItemInstance> unplaced = new ArrayList<>();
        List<List<ItemInstance>> batches = GroupBatcher.batches(items);
        for (int position = 0; position < batches.size(); position++) {
            List<ItemInstance> batch = batches.get(position);
            ContainerState snapshot = state.copy();
            List<Space> saved = spaces;
            boolean placed = true;
            try {
                for (ItemInstance item : batch) {
                    stats.searchNodesExpanded++;
                    Optional<Candidate> chosen = choose(state, spaces, item, config, active, stats, deadline);
                    if (chosen.isEmpty()) {
                        placed = false;
                        break;
                    }
                    state = BeamPacker.extended(state, item, chosen.get());
                    spaces = subtractAll(spaces,
                            List.of(new AxisAlignedBox(chosen.get().point(), chosen.get().envelopeDimensions())),
                            Optional.of(stats));
                }
            } catch (TimeLimitReached reached) {
                List<ItemInstance> pending = new ArrayList<>(unplaced);
                pending.addAll(GroupBatcher.flatten(batches.subList(position, batches.size())));
                return new SingleContainerSolution(snapshot, pending, false, true, false);
            }
            if (!placed) {
                state = snapshot;
                spaces = saved;
                unplaced.addAll(batch);
            }
        }
        return new SingleContainerSolution(state, unplaced);
    }

    private Optional<Candidate> choose(ContainerState state, List<Space> spaces, ItemInstance item,
            PackingConfig config, List<PlacementConstraint> active, SearchStats stats, Deadline deadline) {
        for (Space space : spaces) {
            deadline.check();
            for (Candidate candidate : CandidateFinder.find(state, item, config, active, stats, deadline, scorer,
                    Optional.empty(), Optional.of(List.of(space.origin())))) {
                if (candidate.envelopeDimensions().fitsInside(space.dimensions())) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** The up-to-six maximal slabs of the space that survive carving the box out of it. */
    private static List<Space> subtract(Space space, AxisAlignedBox box) {
        AxisAlignedBox source = new AxisAlignedBox(space.origin(), space.dimensions());
        if (!source.intersects(box)) {
            return List.of(space);
        }
        Point o = source.origin();
        List<Space> out = new ArrayList<>(6);
        push(out, o.x(), o.y(), o.z(), Math.min(source.x2(), box.origin().x()), source.y2(), source.z2());
        push(out, Math.max(o.x(), box.x2()), o.y(), o.z(), source.x2(), source.y2(), source.z2());
        push(out, o.x(), o.y(), o.z(), source.x2(), Math.min(source.y2(), box.origin().y()), source.z2());
        push(out, o.x(), Math.max(o.y(), box.y2()), o.z(), source.x2(), source.y2(), source.z2());
        push(out, o.x(), o.y(), o.z(), source.x2(), source.y2(), Math.min(source.z2(), box.origin().z()));
        push(out, o.x(), o.y(), Math.max(o.z(), box.z2()), source.x2(), source.y2(), source.z2());
        return out;
    }

    private static void push(List<Space> out, long x1, long y1, long z1, long x2, long y2, long z2) {
        if (x2 > x1 && y2 > y1 && z2 > z1) {
            out.add(new Space(new Point(x1, y1, z1), Dimensions.ofTicks(x2 - x1, y2 - y1, z2 - z1)));
        }
    }

    /**
     * Precondition: {@code spaces} is containment-free (a single whole space, or this method's own
     * output). An untouched survivor can then neither contain nor be contained by another, so only
     * new slabs need the dominance check: O(new * s) instead of O(s^2).
     */
    public static List<Space> subtractAll(List<Space> spaces, List<AxisAlignedBox> boxes, Optional<SearchStats> stats) {
        List<Carved> current = new ArrayList<>();
        spaces.forEach(space -> current.add(new Carved(space, false)));
        List<Carved> working = current;
        for (AxisAlignedBox box : boxes) {
            int size = working.size();
            stats.ifPresent(counters -> counters.spacePartitions += size);
            List<Carved> next = new ArrayList<>();
            for (Carved carved : working) {
                if (!new AxisAlignedBox(carved.space().origin(), carved.space().dimensions()).intersects(box)) {
                    next.add(carved);
                    continue;
                }
                subtract(carved.space(), box).forEach(part -> next.add(new Carved(part, true)));
            }
            working = next;
        }
        Map<Space, Carved> unique = new LinkedHashMap<>();
        working.forEach(carved -> unique.put(carved.space(), carved));
        List<Carved> kept = StableSorter.sortBy(new ArrayList<>(unique.values()), carved -> SortKey.of(
                carved.space().origin().z(), carved.space().origin().y(), carved.space().origin().x(),
                carved.space().dimensions().volume().negate()));
        // Without this filter every placement multiplies the list into overlapping duplicates.
        List<Space> result = new ArrayList<>();
        for (int index = 0; index < kept.size(); index++) {
            Carved carved = kept.get(index);
            if (carved.isNew() && dominated(kept, index)) {
                continue;
            }
            result.add(carved.space());
        }
        if (result.size() <= MAX_MAXIMAL_SPACES) {
            return result;
        }
        List<Space> byVolume = StableSorter.sortBy(result, space -> SortKey.of(space.dimensions().volume().negate()));
        return StableSorter.sortBy(new ArrayList<>(byVolume.subList(0, MAX_MAXIMAL_SPACES)),
                space -> SortKey.of(space.origin().z(), space.origin().y(), space.origin().x()));
    }

    private static boolean dominated(List<Carved> kept, int index) {
        AxisAlignedBox mine = box(kept.get(index).space());
        for (int other = 0; other < kept.size(); other++) {
            if (other != index && box(kept.get(other).space()).contains(mine)) {
                return true;
            }
        }
        return false;
    }

    private static AxisAlignedBox box(Space space) {
        return new AxisAlignedBox(space.origin(), space.dimensions());
    }

    /** A space and whether a carve produced it, which is all the dominance check needs to know. */
    private record Carved(Space space, boolean isNew) {
    }
}
