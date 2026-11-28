package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convex hulls in ticks, written the slow, obvious way the reference writes them: a validator that
 * took the engine's shortcuts would stop being a second opinion.
 */
final class Geometry {
    /** Axis permutation each orientation code applies to (length, width, height), in the reference's order. */
    static final Map<String, int[]> ROTATIONS = rotations();
    static final List<String> ORIENTATIONS = List.of("LWH", "LHW", "WLH", "WHL", "HLW", "HWL");

    private Geometry() {
    }

    private static Map<String, int[]> rotations() {
        Map<String, int[]> rotations = new java.util.LinkedHashMap<>();
        rotations.put("LWH", new int[] {0, 1, 2});
        rotations.put("LHW", new int[] {0, 2, 1});
        rotations.put("WLH", new int[] {1, 0, 2});
        rotations.put("WHL", new int[] {1, 2, 0});
        rotations.put("HLW", new int[] {2, 0, 1});
        rotations.put("HWL", new int[] {2, 1, 0});
        return rotations;
    }

    /** {@code ROTATIONS[orientation]}. */
    static int[] axes(String orientation) {
        int[] axes = ROTATIONS.get(orientation);
        if (axes == null) {
            throw MalformedResult.shape("unknown orientation " + PyFormat.string(orientation));
        }
        return axes;
    }

    /** {@code orientation in ROTATIONS}, for any JSON value; an unhashable one is Python's {@code TypeError}. */
    static boolean isRotation(Object orientation) {
        Json.hashKey(orientation);
        return orientation instanceof String code && ROTATIONS.containsKey(code);
    }

    /** The axis reduced by its gcd and signed so its first non-zero component is positive. */
    static Vertex primitiveAxis(Vertex axis) {
        BigInteger divisor = axis.x().gcd(axis.y()).gcd(axis.z());
        if (divisor.signum() == 0) {
            return Vertex.ZERO;
        }
        Vertex reduced = new Vertex(Exact.floorDiv(axis.x(), divisor), Exact.floorDiv(axis.y(), divisor),
                Exact.floorDiv(axis.z(), divisor));
        for (int component = 0; component < 3; component++) {
            int sign = reduced.component(component).signum();
            if (sign != 0) {
                return sign < 0 ? reduced.negate() : reduced;
            }
        }
        return Vertex.ZERO;
    }

    /**
     * Reorient a hull the way {@link #ROTATIONS} reorients its box, flipping one axis on an odd
     * permutation so it stays a proper rotation, then translated so its bounds start at the origin.
     */
    static List<Vertex> rotateHull(List<Vertex> vertices, int[] axes) {
        int inversions = 0;
        for (int first = 0; first < 3; first++) {
            for (int second = first + 1; second < 3; second++) {
                if (axes[first] > axes[second]) {
                    inversions++;
                }
            }
        }
        if (vertices.isEmpty()) {
            throw MalformedResult.shape("a hull without vertices");
        }
        List<Vertex> turned = new ArrayList<>(vertices.size());
        for (Vertex vertex : vertices) {
            BigInteger x = vertex.component(axes[0]);
            turned.add(new Vertex(inversions % 2 == 1 ? x.negate() : x, vertex.component(axes[1]),
                    vertex.component(axes[2])));
        }
        BigInteger[] low = new BigInteger[3];
        for (int axis = 0; axis < 3; axis++) {
            low[axis] = turned.get(0).component(axis);
            for (Vertex vertex : turned) {
                low[axis] = low[axis].min(vertex.component(axis));
            }
        }
        Vertex origin = new Vertex(low[0], low[1], low[2]);
        List<Vertex> translated = new ArrayList<>(turned.size());
        turned.forEach(vertex -> translated.add(vertex.minus(origin)));
        return translated;
    }

    /**
     * Whether two convex solids share interior volume; touching is contact, not collision. Every
     * candidate axis is tried: O(v^3 + e^2) axes, each projected in O(v).
     */
    static boolean solidsOverlap(List<Vertex> left, List<Vertex> right) {
        for (Vertex axis : hullAxes(left, right)) {
            BigInteger[] leftRange = extent(left, axis);
            BigInteger[] rightRange = extent(right, axis);
            if (leftRange[1].compareTo(rightRange[0]) <= 0 || rightRange[1].compareTo(leftRange[0]) <= 0) {
                return false;
            }
        }
        return true;
    }

    /** Exact volume of a convex hull, {@code 6V = sum of a . (b x c)} over its outward faces. */
    static BigInteger hullVolume(List<Vertex> vertices) {
        Set<Vertex> normals = new LinkedHashSet<>();
        for (Vertex[] triple : triples(vertices)) {
            Vertex axis = primitiveAxis(triple[1].minus(triple[0]).cross(triple[2].minus(triple[0])));
            if (!axis.equals(Vertex.ZERO)) {
                normals.add(axis);
            }
        }
        BigInteger sixVolumes = BigInteger.ZERO;
        for (Vertex axis : normals) {
            for (Vertex outward : List.of(axis, axis.negate())) {
                BigInteger highest = extent(vertices, outward)[1];
                List<Vertex> face = new ArrayList<>();
                for (Vertex vertex : vertices) {
                    if (vertex.dot(outward).equals(highest)) {
                        face.add(vertex);
                    }
                }
                if (face.size() < 3) {
                    continue;
                }
                List<Vertex> ordered = windFace(face, outward);
                for (int index = 1; index + 1 < ordered.size(); index++) {
                    sixVolumes = sixVolumes.add(ordered.get(0).dot(ordered.get(index).cross(ordered.get(index + 1))));
                }
            }
        }
        return Exact.floorDiv(sixVolumes.abs(), BigInteger.valueOf(6));
    }

    /**
     * Corners of one planar convex face in cyclic order seen from outside, by gift-wrapping from
     * the lexicographically smallest vertex; collinear candidates resolve to the farthest.
     */
    private static List<Vertex> windFace(List<Vertex> face, Vertex outward) {
        Vertex start = face.get(0);
        for (Vertex vertex : face) {
            if (vertex.compareTo(start) < 0) {
                start = vertex;
            }
        }
        List<Vertex> ordered = new ArrayList<>();
        ordered.add(start);
        Vertex current = start;
        for (int step = 0; step < face.size(); step++) {
            Vertex following = null;
            for (Vertex candidate : face) {
                if (candidate.equals(current)) {
                    continue;
                }
                if (following == null) {
                    following = candidate;
                    continue;
                }
                int turn = following.minus(current).cross(candidate.minus(current)).dot(outward).signum();
                Vertex reachVector = candidate.minus(current);
                Vertex heldVector = following.minus(current);
                if (turn < 0 || (turn == 0 && reachVector.dot(reachVector).compareTo(heldVector.dot(heldVector)) > 0)) {
                    following = candidate;
                }
            }
            if (following == null || following.equals(start)) {
                break;
            }
            ordered.add(following);
            current = following;
        }
        return ordered;
    }

    /** The complete separating-axis superset: face normals of each hull and cross products of their edges. */
    private static Set<Vertex> hullAxes(List<Vertex> left, List<Vertex> right) {
        Set<Vertex> axes = new LinkedHashSet<>();
        for (List<Vertex> hull : List.of(left, right)) {
            for (Vertex[] triple : triples(hull)) {
                axes.add(primitiveAxis(triple[1].minus(triple[0]).cross(triple[2].minus(triple[0]))));
            }
        }
        List<Vertex> rightDirections = pairDirections(right);
        for (Vertex one : pairDirections(left)) {
            for (Vertex other : rightDirections) {
                axes.add(primitiveAxis(one.cross(other)));
            }
        }
        axes.remove(Vertex.ZERO);
        return axes;
    }

    private static List<Vertex> pairDirections(List<Vertex> points) {
        List<Vertex> directions = new ArrayList<>();
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                directions.add(points.get(second).minus(points.get(first)));
            }
        }
        return directions;
    }

    /** Every 3-combination, in {@code itertools.combinations} order. */
    private static List<Vertex[]> triples(List<Vertex> points) {
        List<Vertex[]> triples = new ArrayList<>();
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                for (int third = second + 1; third < points.size(); third++) {
                    triples.add(new Vertex[] {points.get(first), points.get(second), points.get(third)});
                }
            }
        }
        return triples;
    }

    /** The lowest and highest projection of {@code points} on {@code axis}. */
    private static BigInteger[] extent(List<Vertex> points, Vertex axis) {
        BigInteger low = null;
        BigInteger high = null;
        for (Vertex point : points) {
            BigInteger value = point.dot(axis);
            low = low == null || value.compareTo(low) < 0 ? value : low;
            high = high == null || value.compareTo(high) > 0 ? value : high;
        }
        return new BigInteger[] {low, high};
    }
}
