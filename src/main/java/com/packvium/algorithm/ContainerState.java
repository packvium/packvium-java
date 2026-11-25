package com.packvium.algorithm;

import com.packvium.constraint.VolumeReserve;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.HullShape;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.LatticeSummary;
import com.packvium.domain.Nesting;
import com.packvium.domain.Obstacle;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.ShapeType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Placed boxes plus the candidate points they expose; mutable, owned by one search branch.
 *
 * <p>Points are corner points of every solid together with their projections onto the
 * surfaces below, behind and to the left of them. A point is discarded only when it falls
 * outside the container or inside a solid -- never for being "dominated", because a lower-left
 * point can be blocked while the point it would dominate is usable.
 */
public final class ContainerState {
    public final Container container;
    public final long sequence;
    public final List<Placement> placements;
    /** Free points in the order they were found; the axle scan reads them in this order. */
    public final LinkedHashSet<Point> points;
    /** The same points in deterministic (z, y, x) order, which the candidate scan walks. */
    public final List<Point> orderedPoints;
    public final List<AxisAlignedBox> occupied;
    /** Plain integer extents of everything solid; the candidate scan tests these millions of times. */
    public final List<long[]> bounds;
    /** Parallel to {@link #bounds}: the solid's rotated hull, or empty for an ordinary box. */
    public final List<Optional<HullShape>> hullShapes;
    public long payloadTicks;
    /** Exact physical volume, nesting overlap removed. */
    public BigInteger usedVolume;
    public boolean stackSensitive;
    public boolean routeSensitive;
    public boolean compressionSensitive;
    /** Largest envelope x2 and y2 so far; with {@code maxZ}, a flat bag's running extents. */
    public long maxX;
    public long maxY;
    public long maxZ;
    public SpatialIndex index;
    /** Set instead of filling {@link #placements} when the grid's lattice fast path applies. */
    public LatticeSummary latticeSummary;
    public List<ItemInstance> latticeItems;
    private BigInteger usableVolume;

    public ContainerState(Container container, long sequence) {
        this.container = container;
        this.sequence = sequence;
        placements = new ArrayList<>();
        points = new LinkedHashSet<>();
        orderedPoints = new ArrayList<>();
        occupied = new ArrayList<>();
        bounds = new ArrayList<>();
        hullShapes = new ArrayList<>();
        usedVolume = BigInteger.ZERO;
        latticeItems = List.of();
        var inner = container.innerDimensions();
        index = new SpatialIndex(inner.length().ticks(), inner.width().ticks(), inner.height().ticks());
        for (Obstacle obstacle : container.obstacles()) {
            for (AxisAlignedBox box : obstacle.boxes()) {
                occupied.add(box);
                long[] bound = box.extent();
                index.add(bounds.size(), bound);
                bounds.add(bound);
                // An obstacle is always a box, so the parallel lists start the same length.
                hullShapes.add(Optional.empty());
            }
        }
        absorb(List.of(Point.ORIGIN));
        for (AxisAlignedBox box : List.copyOf(occupied)) {
            absorb(exposedPoints(box));
        }
        // Fixed items enter as real placements, so payload, support, top load and every other
        // rule that reads `placements` holds for them with no rule of its own.
        for (Placement placement : container.preloaded()) {
            add(placement);
        }
    }

    private ContainerState(ContainerState source) {
        this(source, source.container);
    }

    private ContainerState(ContainerState source, Container container) {
        this.container = container;
        sequence = source.sequence;
        placements = new ArrayList<>(source.placements);
        points = new LinkedHashSet<>(source.points);
        orderedPoints = new ArrayList<>(source.orderedPoints);
        occupied = new ArrayList<>(source.occupied);
        // Bounds are never mutated after insertion, so the arrays themselves can be shared.
        bounds = new ArrayList<>(source.bounds);
        hullShapes = new ArrayList<>(source.hullShapes);
        payloadTicks = source.payloadTicks;
        usedVolume = source.usedVolume;
        stackSensitive = source.stackSensitive;
        routeSensitive = source.routeSensitive;
        compressionSensitive = source.compressionSensitive;
        maxX = source.maxX;
        maxY = source.maxY;
        maxZ = source.maxZ;
        index = source.index.copy();
        latticeSummary = source.latticeSummary;
        latticeItems = source.latticeItems;
        usableVolume = container == source.container ? source.usableVolume : null;
    }

    /** A fork for a search branch; the constructor's point seeding is not repeated. */
    public ContainerState copy() {
        return new ContainerState(this);
    }

    /**
     * This state as a trial of another container of the same contents: a made-to-measure rung
     * committed as the box cut for it. Only read after the trial ends, so the candidate
     * points and index sized for the rung are never searched again.
     */
    public ContainerState withContainer(Container replacement) {
        return new ContainerState(this, replacement);
    }

    /**
     * What a carrier measures for this container as filled: a flat bag's running extents (empty
     * for an empty bag, which bills nothing by volume), else the declared outer size, else the
     * inner size. O(1).
     */
    public Optional<Dimensions> billedDimensions() {
        if (container.isFlatBag()) {
            return placements.isEmpty() ? Optional.empty() : Optional.of(Dimensions.ofTicks(maxX, maxY, maxZ));
        }
        return Optional.of(container.outerDimensions().orElse(container.innerDimensions()));
    }

    /** Void-fill-reserve headroom; depends only on the container, so it is computed once. */
    public BigInteger usableVolume() {
        if (usableVolume == null) {
            usableVolume = VolumeReserve.usable(container);
        }
        return usableVolume;
    }

    public void add(Placement placement) {
        AxisAlignedBox box = placement.envelopeBox();
        boolean nowCompressionSensitive = compressionSensitive
                || placement.instance().item().shapeType() == ShapeType.COMPRESSIBLE;
        if (nowCompressionSensitive) {
            List<Placement> withNew = new ArrayList<>(placements);
            withNew.add(placement);
            usedVolume = Nesting.usedVolume(TopLoadAssigner.assign(withNew));
        } else {
            usedVolume = usedVolume.add(Nesting.usedVolumeDelta(placements, placement));
        }
        recordPlacement(placement, box);
        compressionSensitive = nowCompressionSensitive;
        occupied.add(box);
        long[] bound = box.extent();
        index.add(bounds.size(), bound);
        bounds.add(bound);
        Optional<HullShape> shape = placement.hullShape();
        hullShapes.add(shape);
        // Retiring a point because it lies inside a solid's box assumes the box is the solid.
        // For a hull it is not: a wedge leaves most of its box, origin included, to the next
        // item, and pruning those points would hide an interlocking pack.
        // `orderedPoints` holds exactly the points of `points`, so one pass over it retires the
        // same set from both. O(P) for P free points.
        if (shape.isEmpty()) {
            orderedPoints.removeIf(point -> box.containsPoint(point) && points.remove(point));
        }
        absorb(exposedPoints(box));
    }

    /**
     * Record a placement for solvers that never query extreme points. The grid builds a
     * non-overlapping lattice directly, so maintaining the point index would be quadratic waste.
     */
    public void addDirect(Placement placement) {
        usedVolume = usedVolume.add(Nesting.usedVolumeDelta(placements, placement));
        recordPlacement(placement, placement.envelopeBox());
    }

    public long placementCount() {
        return latticeSummary != null ? latticeSummary.count() : placements.size();
    }

    /** Record a whole regular-lattice run in O(items) without building a Placement each. */
    public void addLattice(LatticeSummary summary, List<ItemInstance> items) {
        latticeSummary = summary;
        latticeItems = List.copyOf(items);
        payloadTicks = Math.addExact(payloadTicks, summary.totalWeightTicks());
        usedVolume = usedVolume.add(summary.usedVolume());
        for (ItemInstance instance : items) {
            stackSensitive = stackSensitive || instance.item().isStackSensitive();
            routeSensitive = routeSensitive || instance.item().stopIndex().isPresent();
        }
        maxZ = Math.max(maxZ, summary.maxZTicks());
    }

    private void recordPlacement(Placement placement, AxisAlignedBox box) {
        placements.add(placement);
        payloadTicks = Math.addExact(payloadTicks, placement.instance().weight().ticks());
        stackSensitive = stackSensitive || placement.instance().item().isStackSensitive();
        routeSensitive = routeSensitive || placement.instance().item().stopIndex().isPresent();
        maxX = Math.max(maxX, box.x2());
        maxY = Math.max(maxY, box.y2());
        maxZ = Math.max(maxZ, box.z2());
    }

    /**
     * {@code id@x,y,z|...} over the placements in order: the last-resort tie-break every solver's
     * node key ends with. O(m).
     */
    public String placementSignature() {
        StringBuilder signature = new StringBuilder();
        for (Placement placement : placements) {
            if (signature.length() > 0) {
                signature.append('|');
            }
            Point origin = placement.envelopeOrigin();
            signature.append(placement.instance().id()).append('@').append(origin.x()).append(',')
                    .append(origin.y()).append(',').append(origin.z());
        }
        return signature.toString();
    }

    /** O(points x solids in one cell) for the inside test, plus O(points) per sorted insertion. */
    private void absorb(List<Point> candidates) {
        var inner = container.innerDimensions();
        long length = inner.length().ticks();
        long width = inner.width().ticks();
        long height = inner.height().ticks();
        for (Point point : candidates) {
            if (point.x() >= length || point.y() >= width || point.z() >= height || points.contains(point)) {
                continue;
            }
            if (insideSolid(point)) {
                continue;
            }
            points.add(point);
            orderedPoints.add(insertionIndex(point), point);
        }
    }

    /** A solid containing the point is registered in the point's own cell. */
    private boolean insideSolid(Point point) {
        for (int position : index.bucketAt(point.x(), point.y(), point.z())) {
            long[] b = bounds.get(position);
            if (b[0] <= point.x() && point.x() < b[3] && b[1] <= point.y() && point.y() < b[4]
                    && b[2] <= point.z() && point.z() < b[5]) {
                return true;
            }
        }
        return false;
    }

    /** Upper bound in (z, y, x) order, so equal keys keep insertion order. */
    private int insertionIndex(Point point) {
        int low = 0;
        int high = orderedPoints.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (compareZyx(orderedPoints.get(middle), point) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    static int compareZyx(Point left, Point right) {
        int byZ = Long.compare(left.z(), right.z());
        if (byZ != 0) {
            return byZ;
        }
        int byY = Long.compare(left.y(), right.y());
        return byY != 0 ? byY : Long.compare(left.x(), right.x());
    }

    private List<Point> exposedPoints(AxisAlignedBox box) {
        Point o = box.origin();
        long x2 = box.x2();
        long y2 = box.y2();
        long z2 = box.z2();
        return List.of(
                new Point(x2, o.y(), o.z()), new Point(o.x(), y2, o.z()), new Point(o.x(), o.y(), z2),
                new Point(x2, y2, o.z()), new Point(x2, o.y(), z2), new Point(o.x(), y2, z2),
                new Point(x2, o.y(), surfaceZ(x2, o.y(), o.z())),
                new Point(x2, surfaceY(x2, o.z(), o.y()), o.z()),
                new Point(o.x(), y2, surfaceZ(o.x(), y2, o.z())),
                new Point(surfaceX(y2, o.z(), o.x()), y2, o.z()),
                new Point(o.x(), surfaceY(o.x(), z2, o.y()), z2),
                new Point(surfaceX(o.y(), z2, o.x()), o.y(), z2));
    }

    /*
     * Each projection is the highest face at or below a ceiling among the solids whose footprint
     * covers the point on the other two axes. The index's ray under the ceiling holds every such
     * solid, and a maximum is indifferent to seeing one twice.
     */
    private long surfaceZ(long x, long y, long ceiling) {
        long best = 0;
        for (int position : index.columnZ(x, y, ceiling)) {
            long[] b = bounds.get(position);
            if (b[5] <= ceiling && b[5] > best && b[0] <= x && x < b[3] && b[1] <= y && y < b[4]) {
                best = b[5];
            }
        }
        return best;
    }

    private long surfaceY(long x, long z, long ceiling) {
        long best = 0;
        for (int position : index.columnY(x, z, ceiling)) {
            long[] b = bounds.get(position);
            if (b[4] <= ceiling && b[4] > best && b[0] <= x && x < b[3] && b[2] <= z && z < b[5]) {
                best = b[4];
            }
        }
        return best;
    }

    private long surfaceX(long y, long z, long ceiling) {
        long best = 0;
        for (int position : index.columnX(y, z, ceiling)) {
            long[] b = bounds.get(position);
            if (b[3] <= ceiling && b[3] > best && b[1] <= y && y < b[4] && b[2] <= z && z < b[5]) {
                best = b[3];
            }
        }
        return best;
    }
}
