package com.packvium.domain;

import java.util.List;

/**
 * The region between a box's own face and a container wall, and the test for whether
 * anything stands in it.
 *
 * <p>It lives in {@code domain} rather than beside either caller because both the constraint
 * layer and the sequence layer need it, and {@code sequence} already depends on
 * {@code constraint} -- putting it there would invert the one-way dependency direction the
 * architecture fixes. Two implementations of the same corridor arithmetic is how two engines
 * start disagreeing about which loads are legal.
 *
 * <p>{@code direction} names <em>which wall's region</em> a box occupies, not a direction of
 * travel, which is why loading and unloading share one primitive.
 */
public final class SweptRegion {
    /**
     * The six axis-aligned faces a box can leave a container through, in a fixed order.
     * Fixed because callers iterate it to pick the <em>first</em> clear direction.
     */
    public static final List<String> ALL_DIRECTIONS = List.of("+x", "-x", "+y", "-y", "+z", "-z");

    private SweptRegion() {
    }

    /**
     * The swept region as {@code [x1, y1, z1, x2, y2, z2]} in ticks. Throws
     * IllegalArgumentException rather than a sequence-specific error because {@code domain}
     * sits below {@code sequence} and must not reach up to it.
     */
    public static long[] volume(AxisAlignedBox box, Dimensions container, String direction) {
        long x1 = box.origin().x();
        long y1 = box.origin().y();
        long z1 = box.origin().z();
        long x2 = box.x2();
        long y2 = box.y2();
        long z2 = box.z2();
        switch (direction) {
            case "+x" -> {
                x1 = x2;
                x2 = container.length().ticks();
            }
            case "-x" -> {
                x2 = x1;
                x1 = 0;
            }
            case "+y" -> {
                y1 = y2;
                y2 = container.width().ticks();
            }
            case "-y" -> {
                y2 = y1;
                y1 = 0;
            }
            case "+z" -> {
                z1 = z2;
                z2 = container.height().ticks();
            }
            case "-z" -> {
                z2 = z1;
                z1 = 0;
            }
            default -> throw new IllegalArgumentException("unknown movement direction " + direction);
        }
        return new long[] {x1, y1, z1, x2, y2, z2};
    }

    /**
     * Does {@code box} stand anywhere inside a swept region? Half-open on every axis, matching
     * {@link AxisAlignedBox#intersects}, so a box flush against another's exit face is not in
     * its way.
     */
    public static boolean intersects(long[] sweep, AxisAlignedBox box) {
        return sweep[0] < box.x2() && box.origin().x() < sweep[3]
                && sweep[1] < box.y2() && box.origin().y() < sweep[4]
                && sweep[2] < box.z2() && box.origin().z() < sweep[5];
    }
}
