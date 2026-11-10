package com.packvium.domain;

import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nesting overlap accounting.
 *
 * <p>A naive sum of each placement's own volume overstates how much space a nested column
 * actually fills, since neighbouring nested layers share part of the same physical space.
 */
public final class Nesting {
    private Nesting() {
    }

    /**
     * Whether {@code a} and {@code b} are exactly the nested-column relationship
     * {@code nesting_height} describes: the same item type, the same footprint, one sunk into
     * the other by precisely its declared nesting height -- narrow and exact, not a blanket
     * "same item type never collides" bypass.
     */
    public static boolean isValidNesting(Placement a, Placement b) {
        if (!a.instance().item().id().equals(b.instance().item().id())) {
            return false;
        }
        if (a.instance().item().nestingHeight().isEmpty()) {
            return false;
        }
        long nesting = a.instance().item().nestingHeight().get().ticks();
        AxisAlignedBox boxA = a.envelopeBox();
        AxisAlignedBox boxB = b.envelopeBox();
        if (boxA.origin().x() != boxB.origin().x() || boxA.origin().y() != boxB.origin().y()
                || boxA.x2() != boxB.x2() || boxA.y2() != boxB.y2()) {
            return false;
        }
        AxisAlignedBox low = boxA.origin().z() <= boxB.origin().z() ? boxA : boxB;
        AxisAlignedBox high = low == boxA ? boxB : boxA;
        if (low.origin().z() == high.origin().z()) {
            return false;
        }
        return low.z2() - high.origin().z() == nesting;
    }

    /**
     * Total double-counted volume between adjacent nested layers. Grouped by (item, footprint)
     * and checked only against the immediate z-neighbour -- O(n log n), not the O(n^2) an
     * all-pairs scan would cost -- since a valid nest can only ever involve two immediately
     * adjacent layers of an identical footprint.
     */
    private static BigInteger overlapVolume(List<Placement> placements) {
        Map<String, List<Placement>> groups = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (placement.instance().item().nestingHeight().isEmpty()) {
                continue;
            }
            AxisAlignedBox box = placement.envelopeBox();
            String key = placement.instance().item().id() + "|" + box.origin().x() + "," + box.origin().y() + ","
                    + box.x2() + "," + box.y2();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(placement);
        }
        BigInteger overlap = BigInteger.ZERO;
        for (List<Placement> unsorted : groups.values()) {
            if (unsorted.size() < 2) {
                continue;
            }
            List<Placement> group = StableSorter.sortBy(unsorted,
                    placement -> SortKey.of(placement.envelopeBox().origin().z()));
            for (int index = 0; index < group.size() - 1; index++) {
                Placement lower = group.get(index);
                Placement upper = group.get(index + 1);
                if (isValidNesting(lower, upper)) {
                    BigInteger slice = BigInteger.valueOf(lower.instance().item().nestingHeight().get().ticks())
                            .multiply(BigInteger.valueOf(lower.envelopeDimensions().baseAreaTicks()));
                    overlap = overlap.add(slice);
                }
            }
        }
        return overlap;
    }

    /**
     * Space one placement actually takes, which is its box only if it is one.
     *
     * <p>A {@code convex_hull} item occupies its hull: counting the bounding box would put two
     * interlocking wedges at 200% of a crate. A {@code compressible} item occupies the height
     * left after the load it reports, which is what makes compression_ratio observable at all.
     */
    public static BigInteger occupiedVolume(Placement placement) {
        Item item = placement.instance().item();
        // Route and clearance rules may conservatively use the envelope for collision, but
        // they do not change the physical solid reported in utilisation/reserve accounting.
        if (item.shapeType() == ShapeType.CONVEX_HULL) {
            return HullShape.shapeFor(item.hullVertices().orElseThrow(), placement.rotation()).volume();
        }
        if (item.maxCompressionPressureKpa().isEmpty()) {
            return placement.dimensions().volume();
        }
        long limit = item.maxCompressionPressureKpa().get();
        Dimensions dimensions = placement.dimensions();
        long footprint = dimensions.baseAreaTicks();
        Compression.Pressure pressure = Compression.pressure(placement.topLoad().ticks(), footprint);
        // A crushed item has no meaningful occupied volume, and the arrangement is already
        // invalid -- the crush check refuses it and the validator reports it. Reporting the
        // uncompressed figure keeps that a reported issue rather than a second, quieter one.
        if (Compression.exceeds(pressure, limit)) {
            return dimensions.volume();
        }
        long height = Compression.effectiveHeight(dimensions.height().ticks(),
                item.compressionRatioPpm().orElse(0L), limit, pressure);
        return BigInteger.valueOf(footprint).multiply(BigInteger.valueOf(height));
    }

    /** Physical volume actually occupied by {@code placements}, nesting overlap removed. O(n log n). */
    public static BigInteger usedVolume(List<Placement> placements) {
        BigInteger total = BigInteger.ZERO;
        for (Placement placement : placements) {
            total = total.add(occupiedVolume(placement));
        }
        return total.subtract(overlapVolume(placements));
    }

    /**
     * Exact physical-volume delta for appending one placement. Existing overlap pairs do not
     * change. The ordinary non-nesting path is O(1); a nesting-aware append scans the existing
     * placements once in O(n) time and O(1) additional space.
     */
    public static BigInteger usedVolumeDelta(List<Placement> placements, Placement placement) {
        if (placement.instance().item().nestingHeight().isEmpty()) {
            return occupiedVolume(placement);
        }
        long nesting = placement.instance().item().nestingHeight().get().ticks();
        BigInteger overlap = BigInteger.ZERO;
        for (Placement existing : placements) {
            if (!isValidNesting(existing, placement)) {
                continue;
            }
            overlap = overlap.add(BigInteger.valueOf(nesting)
                    .multiply(BigInteger.valueOf(placement.envelopeDimensions().baseAreaTicks())));
        }
        return placement.dimensions().volume().subtract(overlap);
    }
}
