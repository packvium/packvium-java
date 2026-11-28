package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What rests on what, and everything that follows from it: support, top load, stacking limits,
 * load propagation, crush, stack density and route unloading order. Entries are a container's
 * placements by item id, in the reference's dict order; edges refer to them by position.
 */
final class Stacking {
    /** One square metre in length ticks squared. */
    static final BigInteger SQUARE_METRE_TICKS = BigInteger.valueOf(256_000_000_000_000L);
    private static final BigInteger PRESSURE_NUMERATOR = BigInteger.valueOf(980_665).multiply(SQUARE_METRE_TICKS);
    private static final BigInteger PRESSURE_DENOMINATOR = Units.TICKS_PER_KG.multiply(BigInteger.valueOf(100_000_000));
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);

    private Stacking() {
    }

    /** One direct support edge: the lower entry's position and the contact area. */
    record Edge(int lower, BigInteger area) {
    }

    /** The supporters of each entry, and the entries directly above each. */
    record Graph(List<List<Edge>> supporters, List<Set<Integer>> directAbove) {
    }

    /**
     * Exact direct-support edges. Ordinary face contacts keep placement order; nested instances of
     * one column are linked only to their adjacent predecessor, which replaces any shadowed face
     * contact from the same column. O(n log n + q + e) for q same-plane candidates and e edges.
     */
    static Graph supportGraph(List<Entry> entries) {
        Map<BigInteger, List<Integer>> byTop = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            byTop.computeIfAbsent(entries.get(index).envelope().z2, key -> new ArrayList<>()).add(index);
        }
        List<List<Edge>> supporters = new ArrayList<>();
        for (int upper = 0; upper < entries.size(); upper++) {
            List<Edge> edges = new ArrayList<>();
            Cuboid envelope = entries.get(upper).envelope();
            for (int lower : byTop.getOrDefault(envelope.z, List.of())) {
                if (lower == upper) {
                    continue;
                }
                BigInteger area = entries.get(lower).envelope().overlapAreaXy(envelope);
                if (area.signum() > 0) {
                    edges.add(new Edge(lower, area));
                }
            }
            supporters.add(edges);
        }
        Map<Integer, List<Object>> groupKeys = new HashMap<>();
        Map<List<Object>, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.spec() == null || entry.spec().nestingHeight() == null) {
                continue;
            }
            Cuboid envelope = entry.envelope();
            List<Object> key = List.of(Json.hashKey(Json.at(entry.placement(), "item_type")), envelope.x, envelope.y,
                    envelope.x2, envelope.y2, entry.spec().nestingHeight());
            groupKeys.put(index, key);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }
        Map<Integer, Integer> predecessors = new LinkedHashMap<>();
        for (List<Integer> group : groups.values()) {
            List<Integer> ordered = new ArrayList<>(group);
            ordered.sort((a, b) -> {
                int order = entries.get(a).envelope().z.compareTo(entries.get(b).envelope().z);
                return order != 0 ? order : Integer.compare(a, b);
            });
            for (int position = 0; position + 1 < ordered.size(); position++) {
                int lower = ordered.get(position);
                int upper = ordered.get(position + 1);
                BigInteger nesting = entries.get(lower).spec().nestingHeight();
                if (entries.get(lower).envelope().z2.subtract(entries.get(upper).envelope().z).equals(nesting)) {
                    predecessors.put(upper, lower);
                }
            }
        }
        for (Map.Entry<Integer, Integer> link : predecessors.entrySet()) {
            int upper = link.getKey();
            int lower = link.getValue();
            List<Object> key = groupKeys.get(upper);
            List<Edge> edges = new ArrayList<>();
            for (Edge edge : supporters.get(upper)) {
                if (!key.equals(groupKeys.get(edge.lower()))) {
                    edges.add(edge);
                }
            }
            int position = edges.size();
            for (int at = 0; at < edges.size(); at++) {
                if (edges.get(at).lower() > lower) {
                    position = at;
                    break;
                }
            }
            edges.add(position, new Edge(lower, entries.get(upper).envelope().baseArea));
            supporters.set(upper, edges);
        }
        List<Set<Integer>> directAbove = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            directAbove.add(new LinkedHashSet<>());
        }
        for (int upper = 0; upper < supporters.size(); upper++) {
            for (Edge edge : supporters.get(upper)) {
                directAbove.get(edge.lower()).add(upper);
            }
        }
        return new Graph(supporters, directAbove);
    }

    static void checkSupportAndLoad(List<Entry> entries, Graph graph, Ratio globalSupport, List<ResultIssue> issues) {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            ItemSpec spec = entry.spec();
            if (spec == null || entry.envelope().z.signum() == 0) {
                continue;
            }
            String itemId = PyFormat.str(entry.id());
            List<Edge> supporting = graph.supporters().get(index);
            Ratio required = globalSupport.compareTo(spec.minimumSupportRatio()) < 0 ? spec.minimumSupportRatio()
                    : globalSupport;
            checkGroundContact(itemId, entry.envelope(), supporting, entries, spec.groundContactRule(), issues);
            if (required.compareTo(BigInteger.ZERO) > 0) {
                BigInteger area = Exact.sum(supporting.stream().map(Edge::area).toList());
                if (area.compareTo(requiredSupportArea(entry.envelope().baseArea, ContainerCheck.ratioPpm(required))) < 0) {
                    Ratio ratio = Ratio.of(area, entry.envelope().baseArea);
                    issues.add(new ResultIssue("insufficient_support", itemId + ": " + PyFormat.fixed6(ratio.toDouble())
                            + " < " + PyFormat.fixed6(required.toDouble())));
                }
            }
            for (Edge edge : supporting) {
                ItemSpec lowerSpec = entries.get(edge.lower()).spec();
                if (lowerSpec == null) {
                    continue;
                }
                String lowerId = PyFormat.str(entries.get(edge.lower()).id());
                if (!lowerSpec.stackable()) {
                    issues.add(new ResultIssue("non_stackable", itemId + " rests on " + lowerId));
                } else if (!lowerSpec.acceptsStackingGroups().isEmpty()
                        && !Accounting.holds(lowerSpec.acceptsStackingGroups(), spec.stackingGroup())) {
                    issues.add(new ResultIssue("stacking_group_incompatible", itemId + " rests on " + lowerId));
                }
                if (lowerSpec.maxTopLoad() != null && spec.weight().compareTo(lowerSpec.maxTopLoad()) > 0) {
                    issues.add(new ResultIssue("top_load_exceeded", itemId + " (" + spec.weight() + " ticks) on "
                            + lowerId + " (limit " + lowerSpec.maxTopLoad() + ")"));
                }
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            ItemSpec spec = entries.get(index).spec();
            if (spec == null || spec.maxStackedItems() == null) {
                continue;
            }
            Set<Integer> descendants = new java.util.HashSet<>();
            Deque<Integer> pending = new ArrayDeque<>(graph.directAbove().get(index));
            while (!pending.isEmpty()) {
                int above = pending.pop();
                if (descendants.add(above)) {
                    pending.addAll(graph.directAbove().get(above));
                }
            }
            if (BigInteger.valueOf(descendants.size()).compareTo(spec.maxStackedItems()) > 0) {
                issues.add(new ResultIssue("max_stacked_items_exceeded", PyFormat.str(entries.get(index).id()) + ": "
                        + descendants.size() + " above > limit " + spec.maxStackedItems()));
            }
        }
    }

    /** Exact {@code floor(baseArea * ppm / 10^6)}, the least supported area the ratio allows. */
    private static BigInteger requiredSupportArea(BigInteger baseArea, BigInteger ppm) {
        return Exact.floorDiv(baseArea.multiply(ppm), MILLION);
    }

    private static void checkGroundContact(String itemId, Cuboid envelope, List<Edge> supporting, List<Entry> entries,
            Object rule, List<ResultIssue> issues) {
        if (rule == null || Json.isText(rule, "free")) {
            return;
        }
        if (Json.isText(rule, "covered")) {
            BigInteger[][] corners = {{envelope.x, envelope.y}, {envelope.x2, envelope.y}, {envelope.x, envelope.y2},
                {envelope.x2, envelope.y2}};
            for (BigInteger[] corner : corners) {
                boolean covered = false;
                for (Edge edge : supporting) {
                    Cuboid surface = entries.get(edge.lower()).envelope();
                    if (surface.x.compareTo(corner[0]) <= 0 && corner[0].compareTo(surface.x2) <= 0
                            && surface.y.compareTo(corner[1]) <= 0 && corner[1].compareTo(surface.y2) <= 0) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    issues.add(new ResultIssue("ground_contact_violation", itemId + ": covered requires all four base corners"));
                    return;
                }
            }
        } else if (Json.isText(rule, "single") && supporting.size() != 1) {
            issues.add(new ResultIssue("ground_contact_violation", itemId + ": single has " + supporting.size() + " supporters"));
        } else if (Json.isText(rule, "multiple") && supporting.size() < 2) {
            issues.add(new ResultIssue("ground_contact_violation", itemId + ": multiple has " + supporting.size() + " supporters"));
        }
    }

    /**
     * Cumulative weight each item bears from everything resting on it, split across supporters by
     * contact area and conserved exactly (the remainder goes to the last supporter), top-down.
     */
    static List<BigInteger> propagateLoad(List<Entry> entries, Graph graph) {
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            order.add(index);
        }
        order.sort((a, b) -> {
            Cuboid left = entries.get(a).envelope();
            Cuboid right = entries.get(b).envelope();
            int byTop = right.z2.compareTo(left.z2);
            if (byTop != 0) {
                return byTop;
            }
            int byBottom = right.z.compareTo(left.z);
            return byBottom != 0 ? byBottom : Json.compare(entries.get(a).id(), entries.get(b).id());
        });
        List<BigInteger> loads = new ArrayList<>();
        entries.forEach(entry -> loads.add(BigInteger.ZERO));
        for (int upper : order) {
            ItemSpec spec = entries.get(upper).spec();
            if (spec == null) {
                continue;
            }
            List<Edge> supports = graph.supporters().get(upper);
            BigInteger totalArea = Exact.sum(supports.stream().map(Edge::area).toList());
            if (totalArea.signum() == 0) {
                continue;
            }
            BigInteger downward = spec.weight().add(loads.get(upper));
            BigInteger assigned = BigInteger.ZERO;
            for (int position = 0; position < supports.size(); position++) {
                Edge edge = supports.get(position);
                BigInteger share = position == supports.size() - 1 ? downward.subtract(assigned)
                        : Exact.floorDiv(downward.multiply(edge.area()), totalArea);
                assigned = assigned.add(share);
                loads.set(edge.lower(), loads.get(edge.lower()).add(share));
            }
        }
        return loads;
    }

    /**
     * The pressure a footprint carries, in kPa, exactly:
     * {@code load * g * (ticks per metre)^2 / (ticks per kg * g denominator * Pa per kPa * area)}.
     */
    private static Ratio pressure(BigInteger load, BigInteger baseArea) {
        return Ratio.of(load.multiply(PRESSURE_NUMERATOR), PRESSURE_DENOMINATOR.multiply(baseArea));
    }

    /** No compressible item may carry more pressure than it declared. */
    static void checkCrush(Label packedId, List<Entry> entries, List<BigInteger> loads, List<ResultIssue> issues) {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            BigInteger limit = entry.spec() == null ? null : entry.spec().maxCompressionPressureKpa();
            if (limit == null) {
                continue;
            }
            Ratio pressure = pressure(loads.get(index), entry.envelope().baseArea);
            if (pressure.compareTo(limit) > 0) {
                issues.add(new ResultIssue("crush_violation", PyFormat.str(entry.id()) + " in " + packedId + ": "
                        + PyFormat.fixed6(pressure.toDouble()) + " kPa exceeds " + limit + " kPa"));
            }
        }
    }

    /** Occupied height under load, rounded up, never below one tick; a crushed item keeps its height. */
    static BigInteger compressedHeight(Cuboid envelope, ItemSpec spec, BigInteger load) {
        BigInteger limit = spec.maxCompressionPressureKpa();
        if (spec.compressionRatioPpm() == null) {
            // The reference asserts the pair: a pressure limit without a ratio is not a request it reads.
            throw MalformedResult.shape(spec.id() + ": max_compression_pressure_kpa without compression_ratio");
        }
        Ratio pressure = pressure(load, envelope.baseArea);
        if (pressure.compareTo(limit) > 0 || limit.signum() == 0) {
            return envelope.height;
        }
        BigInteger divisor = limit.multiply(MILLION).multiply(pressure.denominator());
        BigInteger retained = divisor.subtract(spec.compressionRatioPpm().multiply(pressure.numerator()));
        return BigInteger.ONE.max(Exact.ceilDiv(envelope.height.multiply(retained), divisor));
    }

    /** Weight per square metre of an item's own base, at every level of the stack. */
    static void checkStackDensity(Label packedId, List<Entry> entries, List<BigInteger> loads, BigInteger maxDensity,
            List<ResultIssue> issues) {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.spec() == null) {
                continue;
            }
            BigInteger total = entry.spec().weight().add(loads.get(index));
            if (total.multiply(SQUARE_METRE_TICKS).compareTo(maxDensity.multiply(entry.envelope().baseArea)) > 0) {
                issues.add(new ResultIssue("stack_density_exceeded", PyFormat.str(entry.id()) + " in " + packedId + ": "
                        + total + " ticks over " + entry.envelope().baseArea + " ticks^2 exceeds " + maxDensity
                        + " ticks/m^2"));
            }
        }
    }

    /**
     * Items due at an earlier stop must all be removable -- unblocked from above and slidable out a
     * door, all six walls when the container names none -- before the route moves on.
     */
    static void checkRouteRemovalOrder(Label packedId, List<Entry> entries, Graph graph, List<BigInteger> container,
            List<String> doors, List<ResultIssue> issues) {
        Map<Integer, BigInteger> stops = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            ItemSpec spec = entries.get(index).spec();
            if (spec != null && spec.stopIndex() != null) {
                stops.put(index, spec.stopIndex());
            }
        }
        if (stops.isEmpty()) {
            return;
        }
        List<String> directions = doors.isEmpty() ? EXITS : doors;
        Set<Integer> present = new LinkedHashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            present.add(index);
        }
        for (BigInteger stop : new TreeSet<>(stops.values())) {
            Set<Integer> due = new LinkedHashSet<>();
            stops.forEach((index, value) -> {
                if (value.equals(stop) && present.contains(index)) {
                    due.add(index);
                }
            });
            while (!due.isEmpty()) {
                Integer chosen = null;
                for (int item : due) {
                    if (graph.directAbove().get(item).stream().anyMatch(present::contains)) {
                        continue;
                    }
                    if (hasClearExit(item, entries, present, container, directions)
                            && (chosen == null || Json.compare(entries.get(item).id(), entries.get(chosen).id()) < 0)) {
                        chosen = item;
                    }
                }
                if (chosen == null) {
                    List<Object> names = new ArrayList<>();
                    due.forEach(index -> names.add(entries.get(index).id()));
                    List<String> listed = Json.sorted(names).stream().map(PyFormat::repr).toList();
                    issues.add(new ResultIssue("unloading_order_violation", packedId + ": stop " + stop
                            + " cannot fully unload " + PyFormat.listOf(listed) + " before the route moves on"));
                    return;
                }
                present.remove(chosen);
                due.remove(chosen);
            }
        }
    }

    private static final List<String> EXITS = Spec.EXIT_DIRECTIONS;

    private static boolean hasClearExit(int item, List<Entry> entries, Set<Integer> present, List<BigInteger> container,
            List<String> directions) {
        for (String direction : directions) {
            Cuboid region = sweptRegion(entries.get(item).envelope(), container, direction);
            boolean blocked = false;
            for (int other : present) {
                if (other != item && region.intersects(entries.get(other).envelope())) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return true;
            }
        }
        return false;
    }

    private static Cuboid sweptRegion(Cuboid envelope, List<BigInteger> container, String direction) {
        BigInteger x1 = envelope.x;
        BigInteger y1 = envelope.y;
        BigInteger z1 = envelope.z;
        BigInteger x2 = envelope.x2;
        BigInteger y2 = envelope.y2;
        BigInteger z2 = envelope.z2;
        switch (direction) {
            case "+x" -> {
                x1 = envelope.x2;
                x2 = container.get(0);
            }
            case "-x" -> {
                x1 = BigInteger.ZERO;
                x2 = envelope.x;
            }
            case "+y" -> {
                y1 = envelope.y2;
                y2 = container.get(1);
            }
            case "-y" -> {
                y1 = BigInteger.ZERO;
                y2 = envelope.y;
            }
            case "+z" -> {
                z1 = envelope.z2;
                z2 = container.get(2);
            }
            default -> {
                z1 = BigInteger.ZERO;
                z2 = envelope.z;
            }
        }
        return new Cuboid(x1, y1, z1, x2.subtract(x1), y2.subtract(y1), z2.subtract(z1));
    }

    /** Whether two stacked instances form a valid nested column. */
    static boolean validNesting(Entry left, Entry right) {
        if (left.spec() == null || right.spec() == null || left.spec().nestingHeight() == null) {
            return false;
        }
        BigInteger nesting = left.spec().nestingHeight();
        if (!left.spec().id().equals(right.spec().id())
                || !Json.equals(Json.at(left.placement(), "item_type"), Json.at(right.placement(), "item_type"))
                || right.spec().nestingHeight() == null || !right.spec().nestingHeight().equals(nesting)) {
            return false;
        }
        Cuboid a = left.envelope();
        Cuboid b = right.envelope();
        if (!a.x.equals(b.x) || !a.y.equals(b.y) || !a.x2.equals(b.x2) || !a.y2.equals(b.y2)) {
            return false;
        }
        Cuboid lower = a.z.compareTo(b.z) <= 0 ? a : b;
        Cuboid upper = lower == a ? b : a;
        return !lower.z.equals(upper.z) && lower.z2.subtract(upper.z).equals(nesting);
    }

    /** Space a placement actually takes: its compressed box, its hull, or its box. */
    static BigInteger occupiedVolume(Cuboid envelope, Object placement, ItemSpec spec, BigInteger load) {
        if (spec != null && spec.maxCompressionPressureKpa() != null) {
            return envelope.baseArea.multiply(compressedHeight(envelope, spec, load));
        }
        if (spec != null && spec.isConvexHull() && spec.hullVertices() != null) {
            return Geometry.hullVolume(Geometry.rotateHull(spec.hullVertices(),
                    Geometry.axes(Json.text(Json.at(placement, "orientation"), "orientation"))));
        }
        List<Vertex> solid = placedHull(envelope, placement, spec);
        if (solid.equals(envelope.vertices())) {
            Object dimensions = Json.at(placement, "dimensions");
            BigInteger volume = BigInteger.ONE;
            for (String side : List.of("length", "width", "height")) {
                volume = volume.multiply(Json.integer(Json.at(Json.at(dimensions, side), "ticks"), "a placement dimension"));
            }
            return volume;
        }
        return Geometry.hullVolume(solid);
    }

    /**
     * The solid a placement occupies, as vertices in container coordinates: the exact hull when the
     * request allows it and the reported box matches, otherwise the envelope's corners.
     */
    static List<Vertex> placedHull(Cuboid envelope, Object placement, ItemSpec spec) {
        if (spec == null || spec.hullVertices() == null || !spec.isConvexHull() || spec.stopIndex() != null) {
            return envelope.vertices();
        }
        int[] axes = Geometry.axes(Json.text(Json.at(placement, "orientation"), "orientation"));
        List<BigInteger> dimensions = spec.dimensions();
        if (!envelope.length.equals(dimensions.get(axes[0])) || !envelope.width.equals(dimensions.get(axes[1]))
                || !envelope.height.equals(dimensions.get(axes[2]))) {
            return envelope.vertices();
        }
        Vertex origin = new Vertex(envelope.x, envelope.y, envelope.z);
        List<Vertex> placed = new ArrayList<>();
        for (Vertex vertex : Geometry.rotateHull(spec.hullVertices(), axes)) {
            placed.add(origin.plus(vertex));
        }
        return placed;
    }
}
