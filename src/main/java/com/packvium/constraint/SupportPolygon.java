package com.packvium.constraint;

import com.packvium.domain.AxisAlignedBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convex hull and tipping geometry.
 *
 * <p>Overlap area is not the same as stability: an item can be supported over enough area and
 * still overhang its own centre of gravity. Every function here is exact integer arithmetic,
 * no trig or division.
 */
public final class SupportPolygon {
    private static final Comparator<Point2D> LEXICOGRAPHIC =
            Comparator.comparingLong(Point2D::x).thenComparingLong(Point2D::y);

    private SupportPolygon() {
    }

    private static long cross(Point2D o, Point2D a, Point2D b) {
        return Math.subtractExact(Math.multiplyExact(a.x() - o.x(), b.y() - o.y()),
                Math.multiplyExact(a.y() - o.y(), b.x() - o.x()));
    }

    /**
     * Andrew's monotone chain, O(p log p). Returns the hull counter-clockwise, duplicates
     * collapsed. Degenerate input (0, 1, or all-collinear points) returns that same degenerate
     * shape rather than raising -- a single contact point or a razor-thin support strip is a
     * real, if precarious, physical case, not an error.
     */
    public static List<Point2D> convexHull(List<Point2D> points) {
        Map<Point2D, Point2D> unique = new LinkedHashMap<>();
        for (Point2D point : points) {
            unique.put(point, point);
        }
        List<Point2D> sorted = new ArrayList<>(unique.values());
        sorted.sort(LEXICOGRAPHIC);
        if (sorted.size() <= 2) {
            return List.copyOf(sorted);
        }
        List<Point2D> lower = chain(sorted);
        List<Point2D> reversed = new ArrayList<>(sorted);
        java.util.Collections.reverse(reversed);
        List<Point2D> upper = chain(reversed);
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        List<Point2D> hull = new ArrayList<>(lower);
        hull.addAll(upper);
        return List.copyOf(hull.isEmpty() ? sorted : hull);
    }

    private static List<Point2D> chain(List<Point2D> points) {
        List<Point2D> half = new ArrayList<>();
        for (Point2D point : points) {
            while (half.size() >= 2 && cross(half.get(half.size() - 2), half.get(half.size() - 1), point) <= 0) {
                half.remove(half.size() - 1);
            }
            half.add(point);
        }
        return half;
    }

    private static boolean onSegment(Point2D p, Point2D a, Point2D b) {
        if (cross(a, b, p) != 0) {
            return false;
        }
        return Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= p.y() && p.y() <= Math.max(a.y(), b.y());
    }

    /** Whether {@code point} lies inside or on the boundary of a hull from {@link #convexHull}. O(h). */
    public static boolean pointInHull(Point2D point, List<Point2D> hull) {
        int n = hull.size();
        if (n == 0) {
            return false;
        }
        if (n == 1) {
            return point.equals(hull.get(0));
        }
        if (n == 2) {
            return onSegment(point, hull.get(0), hull.get(1));
        }
        for (int i = 0; i < n; i++) {
            if (cross(hull.get(i), hull.get((i + 1) % n), point) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Doubled-tick corners of every candidate/supporter overlap rectangle. Doubled so a
     * centroid computed as {@code x1 + x2} is always an exact integer even when a box's length
     * or width is odd.
     */
    public static List<Point2D> contactHullPoints(AxisAlignedBox candidate, List<AxisAlignedBox> supporters) {
        long cx1 = candidate.origin().x();
        long cy1 = candidate.origin().y();
        long cx2 = candidate.x2();
        long cy2 = candidate.y2();
        List<Point2D> points = new ArrayList<>();
        for (AxisAlignedBox box : supporters) {
            long ox1 = Math.max(cx1, box.origin().x());
            long oy1 = Math.max(cy1, box.origin().y());
            long ox2 = Math.min(cx2, box.x2());
            long oy2 = Math.min(cy2, box.y2());
            if (ox2 > ox1 && oy2 > oy1) {
                for (long x : new long[] {ox1, ox2}) {
                    for (long y : new long[] {oy1, oy2}) {
                        points.add(new Point2D(Math.multiplyExact(2, x), Math.multiplyExact(2, y)));
                    }
                }
            }
        }
        return List.copyOf(points);
    }

    /** Twice the box's own footprint centroid, exact for odd as well as even extents. */
    public static Point2D doubledCentroid(AxisAlignedBox box) {
        return new Point2D(box.origin().x() + box.x2(), box.origin().y() + box.y2());
    }
}
