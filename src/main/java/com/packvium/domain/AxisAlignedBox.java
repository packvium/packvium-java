package com.packvium.domain;

/** A box at an origin; extents are half-open, so boxes that touch do not intersect. */
public record AxisAlignedBox(Point origin, Dimensions dimensions) {
    public long x2() {
        return origin.x() + dimensions.length().ticks();
    }

    public long y2() {
        return origin.y() + dimensions.width().ticks();
    }

    public long z2() {
        return origin.z() + dimensions.height().ticks();
    }

    public boolean contains(AxisAlignedBox other) {
        return other.origin.x() >= origin.x() && other.origin.y() >= origin.y() && other.origin.z() >= origin.z()
                && other.x2() <= x2() && other.y2() <= y2() && other.z2() <= z2();
    }

    public boolean intersects(AxisAlignedBox other) {
        return origin.x() < other.x2() && x2() > other.origin.x()
                && origin.y() < other.y2() && y2() > other.origin.y()
                && origin.z() < other.z2() && z2() > other.origin.z();
    }

    public long overlapAreaXY(AxisAlignedBox other) {
        long x = Math.max(0, Math.min(x2(), other.x2()) - Math.max(origin.x(), other.origin.x()));
        long y = Math.max(0, Math.min(y2(), other.y2()) - Math.max(origin.y(), other.origin.y()));
        return Math.multiplyExact(x, y);
    }

    /** Integer extents {x1, y1, z1, x2, y2, z2} for the candidate scan's hot loop. */
    public long[] extent() {
        return new long[] {origin.x(), origin.y(), origin.z(), x2(), y2(), z2()};
    }

    public boolean containsPoint(Point point) {
        return point.x() >= origin.x() && point.x() < x2()
                && point.y() >= origin.y() && point.y() < y2()
                && point.z() >= origin.z() && point.z() < z2();
    }
}
