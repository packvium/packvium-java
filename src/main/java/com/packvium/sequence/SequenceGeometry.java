package com.packvium.sequence;

import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Dimensions;
import com.packvium.domain.SweptRegion;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Accessibility primitives shared by the unloading and loading dependency graphs.
 *
 * <p>{@code direction} names which container wall's region a box's sweep occupies, not a
 * direction of travel: the region between a box and its {@code +x} wall is the same whether the
 * box is leaving through it or arriving through it. The two graphs differ only in which items
 * count as present at each step and which structural dependency gates the step at all.
 */
public final class SequenceGeometry {
    /** Re-exported: the vocabulary lives in {@code domain} so the constraint layer can share it. */
    public static final List<String> ALL_DIRECTIONS = SweptRegion.ALL_DIRECTIONS;

    private SequenceGeometry() {
    }

    public static List<String> validated(List<String> directions) {
        for (String direction : directions) {
            if (!ALL_DIRECTIONS.contains(direction)) {
                throw new InvalidDirectionError(direction);
            }
        }
        return directions;
    }

    /** The region between {@code box}'s own face and the matching container wall along {@code direction}. */
    public static long[] sweptVolume(AxisAlignedBox box, Dimensions container, String direction) {
        validated(List.of(direction));
        return SweptRegion.volume(box, container, direction);
    }

    /** Every other present box whose envelope intersects {@code box}'s sweep, in present order. O(n). */
    public static Set<Integer> blockingIndices(int index, AxisAlignedBox box, List<AxisAlignedBox> boxes,
            Set<Integer> present, Dimensions container, String direction) {
        long[] sweep = sweptVolume(box, container, direction);
        Set<Integer> blocking = new LinkedHashSet<>();
        for (int other : present) {
            if (other != index && SweptRegion.intersects(sweep, boxes.get(other))) {
                blocking.add(other);
            }
        }
        return blocking;
    }

    /**
     * Same predicate as {@link #blockingIndices}, stopping at the first blocker: the safe-order
     * search asks it O(n * d) times per step and never reads the set. O(n).
     */
    public static boolean blocked(int index, AxisAlignedBox box, List<AxisAlignedBox> boxes, Set<Integer> present,
            Dimensions container, String direction) {
        long[] sweep = sweptVolume(box, container, direction);
        for (int other : present) {
            if (other != index && SweptRegion.intersects(sweep, boxes.get(other))) {
                return true;
            }
        }
        return false;
    }

    /** The first allowed direction (in the order given) whose sweep is clear. O(n * d). */
    public static Optional<String> clearDirection(int index, AxisAlignedBox box, List<AxisAlignedBox> boxes,
            Set<Integer> present, Dimensions container, List<String> directions) {
        for (String direction : directions) {
            if (!blocked(index, box, boxes, present, container, direction)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /** Independently validate containment and collisions against the replay state. O(n). */
    public static void validateBoxAtStep(int index, int step, List<AxisAlignedBox> boxes, Set<Integer> present,
            Dimensions container) {
        AxisAlignedBox box = boxes.get(index);
        if (box.x2() > container.length().ticks() || box.y2() > container.width().ticks()
                || box.z2() > container.height().ticks()) {
            throw new SequenceReplayError(index, step, "placement is outside the container");
        }
        for (int other : present) {
            if (other != index && box.intersects(boxes.get(other))) {
                throw new SequenceReplayError(index, step, "placement collides with an already present placement");
            }
        }
    }

    /**
     * Kahn's algorithm: acyclic exactly when every node can be released. O(V + E).
     *
     * <p>Iterative, not a depth-first walk: a stack of sheets is a dependency chain as long as the
     * stack, and recursion would spend one call frame per level. A node named only as a
     * dependency counts as a node too.
     */
    public static boolean isAcyclic(Map<Integer, Set<Integer>> dependsOn) {
        Map<Integer, Integer> waiting = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : dependsOn.entrySet()) {
            waiting.putIfAbsent(entry.getKey(), 0);
            for (int dependency : entry.getValue()) {
                waiting.merge(dependency, 1, Integer::sum);
            }
        }
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        waiting.forEach((node, count) -> {
            if (count == 0) {
                ready.push(node);
            }
        });
        int released = 0;
        while (!ready.isEmpty()) {
            int node = ready.pop();
            released++;
            for (int dependency : dependsOn.getOrDefault(node, Set.of())) {
                if (waiting.merge(dependency, -1, Integer::sum) == 0) {
                    ready.push(dependency);
                }
            }
        }
        return released == waiting.size();
    }
}
