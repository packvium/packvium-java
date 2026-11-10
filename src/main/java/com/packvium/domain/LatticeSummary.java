package com.packvium.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact, O(1)-to-build description of one GridSolver regular-lattice run.
 *
 * <p>What it <em>replaces</em> is the O(n) loop that would construct one Placement per item
 * purely to fill in per-item coordinates. {@link #expand} reconstructs the exact Placement
 * list that loop would have built -- same order, same coordinates, same rotation -- so opting
 * into the compact form never loses information, only defers materializing it.
 *
 * <p>Restricted to items without a nesting height: a nested column's used volume depends on
 * which adjacent pair of layers actually touch, which is exact but not worth the closed-form
 * derivation risk for a still-uncommon feature.
 */
public record LatticeSummary(
        String itemType,
        Rotation rotation,
        Dimensions physical,
        Dimensions envelope,
        long nx,
        long ny,
        long layerStep,
        long clearanceTicks,
        long count,
        long weightTicks) {

    public LatticeSummary {
        if (count <= 0) {
            throw new IllegalArgumentException("a lattice summary must describe at least one placed instance");
        }
        if (nx <= 0 || ny <= 0) {
            throw new IllegalArgumentException("a lattice summary requires positive per-axis capacity");
        }
    }

    private long perLayer() {
        return Math.multiplyExact(nx, ny);
    }

    public long fullLayers() {
        return count / perLayer();
    }

    public long remainder() {
        return count % perLayer();
    }

    public long layersUsed() {
        return fullLayers() + (remainder() > 0 ? 1 : 0);
    }

    public long totalWeightTicks() {
        return Math.multiplyExact(count, weightTicks);
    }

    /** Physical volume occupied: a plain per-item volume sum, since no nesting term applies. */
    public BigInteger usedVolume() {
        return BigInteger.valueOf(count).multiply(physical.volume());
    }

    /** Highest envelope z2, matching the topmost placed item's envelope box. */
    public long maxZTicks() {
        long topLayerIndex = layersUsed() - 1;
        return Math.addExact(Math.multiplyExact(topLayerIndex, layerStep), envelope.height().ticks());
    }

    private static long triangular(long n) {
        return Math.multiplyExact(n, n - 1) / 2;
    }

    /**
     * Closed-form equivalent of {@link CentreOfMass#offsetPpm} for a uniform lattice of
     * identical items, without expanding a single Placement: the per-item formula reduces to
     * {@code weight * (2*envelopeStep*sum(index) + count*(2*clearance + dimension))}, a closed
     * form over full layers plus one partial layer, since the grid fills x fastest, then y,
     * then z.
     */
    public long centreOfMassOffsetPpm(long innerLengthTicks, long innerWidthTicks) {
        if (weightTicks == 0 || count == 0) {
            return 0;
        }
        long fullLayers = fullLayers();
        long remainder = remainder();
        long rowsInPartial = remainder / nx;
        long extraCols = remainder % nx;
        long sumX = Math.addExact(Math.addExact(
                Math.multiplyExact(Math.multiplyExact(fullLayers, ny), triangular(nx)),
                Math.multiplyExact(rowsInPartial, triangular(nx))), triangular(extraCols));
        long sumY = Math.addExact(Math.addExact(
                Math.multiplyExact(Math.multiplyExact(fullLayers, nx), triangular(ny)),
                Math.multiplyExact(nx, triangular(rowsInPartial))), Math.multiplyExact(extraCols, rowsInPartial));
        BigInteger weight = BigInteger.valueOf(weightTicks);
        BigInteger doubledWeightedX = weight.multiply(BigInteger.valueOf(doubledAxisSum(
                envelope.length().ticks(), sumX, physical.length().ticks())));
        BigInteger doubledWeightedY = weight.multiply(BigInteger.valueOf(doubledAxisSum(
                envelope.width().ticks(), sumY, physical.width().ticks())));
        BigInteger totalWeight = BigInteger.valueOf(totalWeightTicks());
        long offsetX = CentreOfMass.axisOffsetPpm(doubledWeightedX, totalWeight, innerLengthTicks);
        long offsetY = CentreOfMass.axisOffsetPpm(doubledWeightedY, totalWeight, innerWidthTicks);
        return Math.max(offsetX, offsetY);
    }

    private long doubledAxisSum(long step, long indexSum, long dimension) {
        return Math.addExact(Math.multiplyExact(Math.multiplyExact(2, step), indexSum),
                Math.multiplyExact(count, Math.addExact(Math.multiplyExact(2, clearanceTicks), dimension)));
    }

    /** The exact Placement list the per-item loop would have produced, from {@code items[0:count]}. O(count). */
    public List<Placement> expand(List<ItemInstance> items) {
        List<Placement> out = new ArrayList<>();
        for (long index = 0; index < count; index++) {
            long x = index % nx;
            long y = (index / nx) % ny;
            long z = index / (nx * ny);
            Point point = new Point(x * envelope.length().ticks(), y * envelope.width().ticks(), z * layerStep);
            Point position = new Point(point.x() + clearanceTicks, point.y() + clearanceTicks,
                    point.z() + clearanceTicks);
            out.add(new Placement(items.get(Math.toIntExact(index)), position, rotation, physical, point, envelope,
                    1.0));
        }
        return List.copyOf(out);
    }
}
