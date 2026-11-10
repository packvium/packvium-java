package com.packvium.domain;

import com.packvium.support.SortKey;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact separating-axis collision and volume for a {@code convex_hull} item.
 *
 * <p>A separating axis is a cross product of two edge vectors, so its components grow as the
 * square of a coordinate, and a projection {@code v . d} grows as the cube: on a 100 mm hull
 * whose vertex coordinates are coprime the worst projection is 2.5e19 against a long ceiling
 * of 9.2e18. So each shape carries an a-priori bound on its own projections, and every axis
 * is checked against it before any arithmetic happens. Inside the bound the work is native
 * longs; outside it the same work is done in {@link BigInteger}. The answer is identical
 * either way -- the fallback exists so that "the axes happened to reduce well" is never
 * load-bearing.
 *
 * <p>Axes are carried as {@link Vertex} values: both are exact integer triples.
 */
public final class HullShape {
    /** Smallest number of vertices that can enclose a volume. */
    public static final int MINIMUM_VERTICES = 4;

    /**
     * Largest vertex coordinate a hull may carry, in ticks -- 6.25 m. Below it every separating
     * axis is a native long, so only projections onto those axes can need exact arithmetic.
     */
    public static final long MAX_COORDINATE = 100_000_000;

    /** Both the face normals and the edge directions of any axis-aligned box. */
    private static final long[][] UNIT_AXES = {{0, 0, 1}, {0, 1, 0}, {1, 0, 0}};

    /**
     * How many rotated hulls stay resident, before the memo is dropped and refilled. Bounded
     * rather than growing for the life of the process: the point of the memo is to spend
     * less, not to spend it somewhere else.
     */
    private static final int SHAPE_CACHE_ENTRIES = 1024;

    private static final Map<ShapeKey, HullShape> SHAPE_CACHE = new HashMap<>();

    private static final Comparator<long[]> VECTOR_ORDER = (left, right) -> {
        for (int index = 0; index < 3; index++) {
            int order = Long.compare(left[index], right[index]);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    };

    private final long[][] vertices;
    private final long[][] faceAxes;
    private final long[][] edgeDirections;
    private final BigInteger volume;
    private final long maxCoordinate;

    private HullShape(long[][] vertices, long[][] faceAxes, long[][] edgeDirections, BigInteger volume,
            long maxCoordinate) {
        this.vertices = vertices;
        this.faceAxes = faceAxes;
        this.edgeDirections = edgeDirections;
        this.volume = volume;
        this.maxCoordinate = maxCoordinate;
    }

    private record ShapeKey(Rotation rotation, List<Vertex> vertices) {
    }

    /**
     * The rotated hull of one item in one orientation, built at most once.
     *
     * <p>A hull depends on the item and the orientation and on nothing about where a candidate
     * sits, but the collision predicate would otherwise rebuild it on every call -- O(v^4)
     * work inside an O(n^2) loop. Memoisation is safe: a HullShape is immutable, the key is
     * the whole of what determines the value, and determinism is untouched -- this changes
     * how often the answer is computed, never what it is.
     */
    public static HullShape shapeFor(List<Vertex> vertices, Rotation rotation) {
        ShapeKey key = new ShapeKey(rotation, List.copyOf(vertices));
        synchronized (SHAPE_CACHE) {
            HullShape cached = SHAPE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        HullShape shape = of(rotate(vertices, rotation));
        synchronized (SHAPE_CACHE) {
            if (SHAPE_CACHE.size() >= SHAPE_CACHE_ENTRIES) {
                SHAPE_CACHE.clear();
            }
            SHAPE_CACHE.put(key, shape);
        }
        return shape;
    }

    /** Builds a hull from its vertices. O(v^4): every vertex triple is tested as a face plane. */
    public static HullShape of(List<Vertex> vertices) {
        long[][] points = toArrays(validate(vertices));
        Map<long[], long[]> faces = new TreeMap<>(VECTOR_ORDER);
        int count = points.length;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                for (int k = j + 1; k < count; k++) {
                    long[] axis = primitive(cross(difference(points[j], points[i]), difference(points[k], points[i])));
                    // A triple whose plane cuts through the solid is not a face, and its
                    // normal separates nothing the real face normals do not.
                    if (axis != null && isSupporting(points, points[i], axis)) {
                        faces.put(axis, axis);
                    }
                }
            }
        }
        long[][] faceAxes = faces.values().toArray(new long[0][]);
        List<long[][]> wound = woundFaces(points, faceAxes);
        long[][] edgeDirections = edgeDirections(wound);
        return new HullShape(points, faceAxes, edgeDirections, volumeOf(wound), largestComponent(points));
    }

    /**
     * A cuboid, built without searching for its own faces: a box's face normals and edge
     * directions are both exactly the three unit axes.
     */
    public static HullShape box(long length, long width, long height) {
        long[][] corners = new long[8][];
        int next = 0;
        for (long x = 0; x <= 1; x++) {
            for (long y = 0; y <= 1; y++) {
                for (long z = 0; z <= 1; z++) {
                    corners[next++] = new long[] {x * length, y * width, z * height};
                }
            }
        }
        BigInteger volume = BigInteger.valueOf(length).multiply(BigInteger.valueOf(width))
                .multiply(BigInteger.valueOf(height));
        return new HullShape(corners, UNIT_AXES, UNIT_AXES, volume, Math.max(length, Math.max(width, height)));
    }

    /**
     * Reorient a hull the way {@link Dimensions#rotated} reorients its box, never mirroring it.
     *
     * <p>Three of the six rotations are odd permutations of the coordinate axes; on a hull a
     * bare permutation returns the item's mirror image, a shape the caller does not own. One
     * axis therefore changes sign when the permutation is odd, which makes all six proper
     * rotations. Vertices come back translated so the bounding box starts at the origin.
     */
    public static List<Vertex> rotate(List<Vertex> vertices, Rotation rotation) {
        int[] axes = switch (rotation) {
            case LWH -> new int[] {0, 1, 2};
            case LHW -> new int[] {0, 2, 1};
            case WLH -> new int[] {1, 0, 2};
            case WHL -> new int[] {1, 2, 0};
            case HLW -> new int[] {2, 0, 1};
            case HWL -> new int[] {2, 1, 0};
        };
        int inversions = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (axes[i] > axes[j]) {
                    inversions++;
                }
            }
        }
        long sign = inversions % 2 == 1 ? -1 : 1;
        List<long[]> turned = new ArrayList<>(vertices.size());
        for (Vertex vertex : vertices) {
            turned.add(new long[] {sign * vertex.component(axes[0]), vertex.component(axes[1]),
                vertex.component(axes[2])});
        }
        long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        for (long[] vertex : turned) {
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], vertex[axis]);
            }
        }
        List<Vertex> out = new ArrayList<>(turned.size());
        for (long[] vertex : turned) {
            out.add(new Vertex(vertex[0] - low[0], vertex[1] - low[1], vertex[2] - low[2]));
        }
        return List.copyOf(out);
    }

    /**
     * Canonicalise an authored vertex list or refuse a hull with no interior. A zero-volume
     * hull is separated from everything on its own normal, so it would pass through every
     * other item and still be reported as a valid placement. O(n^4) worst case, O(n) typical.
     */
    public static List<Vertex> validate(List<Vertex> vertices) {
        List<Vertex> points = List.copyOf(vertices);
        int count = points.size();
        if (count < MINIMUM_VERTICES) {
            throw new IllegalArgumentException(
                    "a convex hull needs at least " + MINIMUM_VERTICES + " vertices, got " + count);
        }
        Set<Vertex> seen = new HashSet<>(points);
        if (seen.size() != count) {
            throw new IllegalArgumentException("convex hull vertices must be unique");
        }
        long[][] arrays = toArrays(points);
        if (largestComponent(arrays) > MAX_COORDINATE) {
            throw new IllegalArgumentException(
                    "convex hull coordinates must stay within " + MAX_COORDINATE + " ticks");
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                for (int k = j + 1; k < count; k++) {
                    for (int l = k + 1; l < count; l++) {
                        // Exact: this product reaches `12 * C^3` and the only thing asked of
                        // it is whether it is zero, so a silently wrapped value would admit a
                        // flat hull -- one that passes through every other item.
                        BigInteger volume = exactDot(difference(arrays[l], arrays[i]),
                                cross(difference(arrays[j], arrays[i]), difference(arrays[k], arrays[i])));
                        if (volume.signum() != 0) {
                            return points;
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("convex hull vertices are coplanar and enclose no volume");
    }

    /**
     * Do two placed hulls overlap with positive volume?
     *
     * <p>Touching is contact, not collision: the comparison is {@code <=}, keeping hulls
     * consistent with the half-open convention cuboids use, so a hull resting exactly on a box
     * is supported rather than colliding with it. O(a * v) for a separating axes over v
     * vertices, where a is at most the product of the two hulls' edge counts plus their faces.
     */
    public static boolean collide(HullShape left, long[] leftOrigin, HullShape right, long[] rightOrigin) {
        long reach = Math.addExact(Math.max(left.maxCoordinate, right.maxCoordinate),
                Math.max(largestComponent(new long[][] {leftOrigin}), largestComponent(new long[][] {rightOrigin})));
        for (long[] axis : separatingAxisArrays(left, right)) {
            if (!axisFitsNatively(reach, axis)) {
                if (separatedExactly(left, leftOrigin, right, rightOrigin, axis)) {
                    return false;
                }
                continue;
            }
            long[] leftRange = left.projectionOf(axis);
            long[] rightRange = right.projectionOf(axis);
            long leftShift = dot(leftOrigin, axis);
            long rightShift = dot(rightOrigin, axis);
            if (leftRange[1] + leftShift <= rightRange[0] + rightShift
                    || rightRange[1] + rightShift <= leftRange[0] + leftShift) {
                return false;
            }
        }
        return true;
    }

    /** Both hulls' face normals plus every edge-against-edge direction, deduplicated. */
    public static List<Vertex> separatingAxes(HullShape left, HullShape right) {
        List<Vertex> axes = new ArrayList<>();
        for (long[] axis : separatingAxisArrays(left, right)) {
            axes.add(new Vertex(axis[0], axis[1], axis[2]));
        }
        return List.copyOf(axes);
    }

    /**
     * Ordered by the decimal key, as the reference engine's {@code ksort} leaves them. The
     * predicate is an `and` over the whole set, so this order never changes an answer.
     */
    private static List<long[]> separatingAxisArrays(HullShape left, HullShape right) {
        Map<String, long[]> axes = new LinkedHashMap<>();
        for (long[][] group : new long[][][] {left.faceAxes, right.faceAxes}) {
            for (long[] axis : group) {
                axes.put(key(axis), axis);
            }
        }
        for (long[] leftEdge : left.edgeDirections) {
            for (long[] rightEdge : right.edgeDirections) {
                long[] axis = primitive(cross(leftEdge, rightEdge));
                if (axis != null) {
                    axes.put(key(axis), axis);
                }
            }
        }
        List<String> keys = new ArrayList<>(axes.keySet());
        keys.sort(SortKey::compareCodePoints);
        List<long[]> ordered = new ArrayList<>(keys.size());
        for (String name : keys) {
            ordered.add(axes.get(name));
        }
        return ordered;
    }

    /** Closed projection interval {@code [low, high]} on {@code axis}, in this shape's own coordinates. */
    public long[] projection(Vertex axis) {
        return projectionOf(new long[] {axis.x(), axis.y(), axis.z()});
    }

    private long[] projectionOf(long[] axis) {
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (long[] vertex : vertices) {
            long value = dot(vertex, axis);
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        return new long[] {low, high};
    }

    /** Exact volume in cubic ticks. */
    public BigInteger volume() {
        return volume;
    }

    public List<Vertex> vertices() {
        return toVertices(vertices);
    }

    public List<Vertex> faceAxes() {
        return toVertices(faceAxes);
    }

    public List<Vertex> edgeDirections() {
        return toVertices(edgeDirections);
    }

    /** Inclusive lower and upper corners of the hull's axis-aligned envelope. */
    public static Vertex[] boundingExtent(List<Vertex> vertices) {
        long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        long[] high = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (Vertex vertex : vertices) {
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], vertex.component(axis));
                high[axis] = Math.max(high[axis], vertex.component(axis));
            }
        }
        return new Vertex[] {new Vertex(low[0], low[1], low[2]), new Vertex(high[0], high[1], high[2])};
    }

    /**
     * Is every projection onto {@code axis} provably inside a long?
     *
     * <p>{@code |v . d| <= 3 * maxCoordinate * maxAxisComponent}, tested by division so that
     * checking for overflow cannot itself overflow. Per axis rather than per shape, because the
     * gcd reduction is what decides: a wedge's normals come out as (1, 1, 0) and stay native at
     * any size, while an axis that stays large needs the exact path even on a small hull.
     */
    private static boolean axisFitsNatively(long maxCoordinate, long[] axis) {
        long largest = largestComponent(new long[][] {axis});
        if (largest == 0) {
            return true;
        }
        if (largest > Long.MAX_VALUE / 4) {
            return false;
        }
        return maxCoordinate <= Long.MAX_VALUE / (3 * largest);
    }

    private static boolean separatedExactly(HullShape left, long[] leftOrigin, HullShape right, long[] rightOrigin,
            long[] axis) {
        BigInteger[] leftRange = left.exactProjection(axis, leftOrigin);
        BigInteger[] rightRange = right.exactProjection(axis, rightOrigin);
        return leftRange[1].compareTo(rightRange[0]) <= 0 || rightRange[1].compareTo(leftRange[0]) <= 0;
    }

    private BigInteger[] exactProjection(long[] axis, long[] origin) {
        BigInteger low = null;
        BigInteger high = null;
        for (long[] vertex : vertices) {
            BigInteger value = BigInteger.ZERO;
            for (int index = 0; index < 3; index++) {
                value = value.add(BigInteger.valueOf(Math.addExact(vertex[index], origin[index]))
                        .multiply(BigInteger.valueOf(axis[index])));
            }
            if (low == null || value.compareTo(low) < 0) {
                low = value;
            }
            if (high == null || value.compareTo(high) > 0) {
                high = value;
            }
        }
        return new BigInteger[] {low, high};
    }

    /**
     * Every face of the hull, each as its own corners in outward cyclic order.
     *
     * <p>One walk, because the faces answer two questions at once: the volume needs them wound
     * consistently, and the hull's edges are the consecutive corner pairs of the same walk.
     * Each canonical face axis stands for up to two opposite faces, so both the maximal and the
     * minimal supporting plane along it are collected; a plane carrying fewer than three
     * vertices is an edge or a corner of the hull, not a face.
     */
    private static List<long[][]> woundFaces(long[][] vertices, long[][] faceAxes) {
        List<long[][]> faces = new ArrayList<>();
        long reach = largestComponent(vertices);
        for (long[] axis : faceAxes) {
            for (long[] outward : new long[][] {axis, {-axis[0], -axis[1], -axis[2]}}) {
                List<long[]> face = new ArrayList<>();
                if (axisFitsNatively(reach, outward)) {
                    long extreme = Long.MIN_VALUE;
                    long[] projections = new long[vertices.length];
                    for (int index = 0; index < vertices.length; index++) {
                        projections[index] = dot(vertices[index], outward);
                        extreme = Math.max(extreme, projections[index]);
                    }
                    for (int index = 0; index < vertices.length; index++) {
                        if (projections[index] == extreme) {
                            face.add(vertices[index]);
                        }
                    }
                } else {
                    BigInteger extreme = null;
                    BigInteger[] projections = new BigInteger[vertices.length];
                    for (int index = 0; index < vertices.length; index++) {
                        projections[index] = exactDot(vertices[index], outward);
                        if (extreme == null || projections[index].compareTo(extreme) > 0) {
                            extreme = projections[index];
                        }
                    }
                    for (int index = 0; index < vertices.length; index++) {
                        if (projections[index].equals(extreme)) {
                            face.add(vertices[index]);
                        }
                    }
                }
                if (face.size() < 3) {
                    continue;
                }
                faces.add(windFace(face, outward));
            }
        }
        return faces;
    }

    /**
     * Exact volume in cubic ticks, by the divergence theorem over the hull's own faces:
     * {@code 6V = sum over outward-oriented surface triangles of a . (b x c)}, an integer for
     * integer vertices and therefore exact.
     */
    private static BigInteger volumeOf(List<long[][]> faces) {
        BigInteger total = BigInteger.ZERO;
        for (long[][] ordered : faces) {
            long[] apex = ordered[0];
            for (int index = 1; index + 1 < ordered.length; index++) {
                total = total.add(exactDot(apex, cross(ordered[index], ordered[index + 1])));
            }
        }
        return total.abs().divide(BigInteger.valueOf(6));
    }

    /**
     * Directions of the hull's real edges, deduplicated and canonical.
     *
     * <p>Every edge of a convex polyhedron is shared by exactly two faces, so walking each
     * wound face and taking its consecutive corner pairs reaches all of them. A hull has at
     * most {@code 3v - 6} edges but {@code v(v - 1) / 2} vertex pairs, and the axis set is the
     * product of two hulls' sets, so the gap squares.
     */
    private static long[][] edgeDirections(List<long[][]> faces) {
        Map<long[], long[]> edges = new TreeMap<>(VECTOR_ORDER);
        for (long[][] ordered : faces) {
            int size = ordered.length;
            for (int index = 0; index < size; index++) {
                long[] axis = primitive(difference(ordered[(index + 1) % size], ordered[index]));
                if (axis != null) {
                    edges.put(axis, axis);
                }
            }
        }
        return edges.values().toArray(new long[0][]);
    }

    /**
     * One planar convex face, wound counter-clockwise seen from outside, decided with integer
     * cross products rather than an angle.
     *
     * <p>The vertices sharing a supporting plane are not all corners of the polygon they lie
     * on, and fanning over the raw set triangulates the wrong region; gift-wrapping keeps only
     * the corners. It starts from the smallest vertex under a genuine linear order on the
     * vector -- extreme in any such order, therefore a corner -- never its decimal encoding,
     * which can name a vertex part-way along an edge.
     */
    private static long[][] windFace(List<long[]> unordered, long[] outward) {
        List<long[]> face = new ArrayList<>(unordered);
        face.sort(VECTOR_ORDER);
        long[] start = face.get(0);
        List<long[]> ordered = new ArrayList<>();
        ordered.add(start);
        long[] current = start;
        int count = face.size();
        for (int step = 0; step < count; step++) {
            long[] following = null;
            for (long[] candidate : face) {
                if (candidate == current) {
                    continue;
                }
                if (following == null) {
                    following = candidate;
                    continue;
                }
                int turn = signAlong(cross(difference(following, current), difference(candidate, current)), outward);
                // Collinear with the current corner: take the farthest, so a vertex lying on
                // an edge is walked past rather than doubled back through.
                if (turn < 0 || (turn == 0 && squareLength(difference(candidate, current))
                        > squareLength(difference(following, current)))) {
                    following = candidate;
                }
            }
            if (following == null || following == start) {
                break;
            }
            ordered.add(following);
            current = following;
        }
        return ordered.toArray(new long[0][]);
    }

    private static long squareLength(long[] vector) {
        return Math.addExact(Math.addExact(Math.multiplyExact(vector[0], vector[0]),
                Math.multiplyExact(vector[1], vector[1])), Math.multiplyExact(vector[2], vector[2]));
    }

    /**
     * The sign of {@code parallel . axis}, where {@code parallel} is known to be parallel to
     * {@code axis}: the product of the signs of any one matching pair of non-zero components,
     * obtained without multiplying two large numbers together.
     */
    private static int signAlong(long[] parallel, long[] axis) {
        for (int index = 0; index < 3; index++) {
            if (axis[index] == 0 || parallel[index] == 0) {
                continue;
            }
            return (parallel[index] > 0) == (axis[index] > 0) ? 1 : -1;
        }
        return 0;
    }

    private static BigInteger exactDot(long[] vector, long[] axis) {
        BigInteger value = BigInteger.ZERO;
        for (int index = 0; index < 3; index++) {
            value = value.add(BigInteger.valueOf(vector[index]).multiply(BigInteger.valueOf(axis[index])));
        }
        return value;
    }

    private static long largestComponent(long[][] vectors) {
        long largest = 0;
        for (long[] vector : vectors) {
            for (long component : vector) {
                largest = Math.max(largest, Math.abs(component));
            }
        }
        return largest;
    }

    private static boolean isSupporting(long[][] vertices, long[] origin, long[] axis) {
        boolean above = false;
        boolean below = false;
        boolean nativeAxis = axisFitsNatively(largestComponent(vertices), axis);
        long nativeOffset = nativeAxis ? dot(origin, axis) : 0;
        BigInteger exactOffset = nativeAxis ? null : exactDot(origin, axis);
        for (long[] vertex : vertices) {
            int side = nativeAxis
                    ? Long.compare(dot(vertex, axis), nativeOffset)
                    : exactDot(vertex, axis).compareTo(exactOffset);
            if (side > 0) {
                above = true;
            } else if (side < 0) {
                below = true;
            }
            if (above && below) {
                return false;
            }
        }
        return true;
    }

    /**
     * Divide out the gcd and fix the sign, so parallel axes collapse to one entry. Null for the
     * zero vector: a cross product of two parallel directions names no axis, an ordinary
     * outcome here rather than an error.
     */
    private static long[] primitive(long[] axis) {
        long divisor = gcd(gcd(Math.abs(axis[0]), Math.abs(axis[1])), Math.abs(axis[2]));
        if (divisor == 0) {
            return null;
        }
        long[] reduced = {axis[0] / divisor, axis[1] / divisor, axis[2] / divisor};
        for (long component : reduced) {
            if (component > 0) {
                return reduced;
            }
            if (component < 0) {
                return new long[] {-reduced[0], -reduced[1], -reduced[2]};
            }
        }
        return null;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    private static long[] difference(long[] left, long[] right) {
        return new long[] {left[0] - right[0], left[1] - right[1], left[2] - right[2]};
    }

    private static long[] cross(long[] left, long[] right) {
        return new long[] {
            Math.subtractExact(Math.multiplyExact(left[1], right[2]), Math.multiplyExact(left[2], right[1])),
            Math.subtractExact(Math.multiplyExact(left[2], right[0]), Math.multiplyExact(left[0], right[2])),
            Math.subtractExact(Math.multiplyExact(left[0], right[1]), Math.multiplyExact(left[1], right[0])),
        };
    }

    /** Native dot product; callers establish via {@link #axisFitsNatively} that it cannot wrap. */
    private static long dot(long[] point, long[] axis) {
        return point[0] * axis[0] + point[1] * axis[1] + point[2] * axis[2];
    }

    private static String key(long[] vector) {
        return vector[0] + "," + vector[1] + "," + vector[2];
    }

    private static long[][] toArrays(List<Vertex> points) {
        long[][] arrays = new long[points.size()][];
        for (int index = 0; index < arrays.length; index++) {
            Vertex vertex = points.get(index);
            arrays[index] = new long[] {vertex.x(), vertex.y(), vertex.z()};
        }
        return arrays;
    }

    private static List<Vertex> toVertices(long[][] arrays) {
        List<Vertex> out = new ArrayList<>(arrays.length);
        for (long[] vector : arrays) {
            out.add(new Vertex(vector[0], vector[1], vector[2]));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public String toString() {
        return "HullShape" + Arrays.deepToString(vertices);
    }
}
