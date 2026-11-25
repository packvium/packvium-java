package com.packvium.domain;

import java.util.List;

/**
 * The flat-bag girth rule.
 *
 * <p>A {@code flat_bag} container is two sheets of usable flat size L x W filled to at most
 * thickness H, its inner dimensions. With a, b and c the largest envelope x2, y2 and z2 over its
 * placements, measured from the container origin, a packing is admitted only when the ordinary
 * boundary holds and a + c <= L and b + c <= W. Integer additions and comparisons, so every
 * check here is O(1). The extents only grow as boxes are added, so a verdict reached for a
 * candidate never flips when more items follow.
 */
public final class FlatBag {
    public static final String KIND = "flat_bag";
    public static final List<String> KINDS = List.of(KIND);

    private FlatBag() {
    }

    /** Whether a package of extents (a, b, c) passes both girth sums; the frame is the caller's. */
    public static boolean girthAdmits(Dimensions inner, long a, long b, long c) {
        return a + c <= inner.length().ticks() && b + c <= inner.width().ticks();
    }

    /** Whether one box of these dimensions, alone at the origin, fits a flat bag. */
    public static boolean fitsAlone(Dimensions inner, Dimensions box) {
        return box.fitsInside(inner)
                && girthAdmits(inner, box.length().ticks(), box.width().ticks(), box.height().ticks());
    }

    /** The largest envelope x2, y2 and z2 over the placements, all zero for none. O(p). */
    public static Sides extentOf(List<Placement> placements) {
        long a = 0;
        long b = 0;
        long c = 0;
        for (Placement placement : placements) {
            AxisAlignedBox envelope = placement.envelopeBox();
            a = Math.max(a, envelope.x2());
            b = Math.max(b, envelope.y2());
            c = Math.max(c, envelope.z2());
        }
        return new Sides(a, b, c);
    }
}
