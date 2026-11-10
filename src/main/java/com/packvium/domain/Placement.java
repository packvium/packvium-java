package com.packvium.domain;

import com.packvium.unit.Weight;
import java.util.Optional;

/** One item instance at a position and rotation, with the clearance envelope it reserves. */
public record Placement(
        ItemInstance instance,
        Point position,
        Rotation rotation,
        Dimensions dimensions,
        Point envelopeOrigin,
        Dimensions envelopeDimensions,
        double supportRatio,
        Weight topLoad) {

    public Placement(ItemInstance instance, Point position, Rotation rotation, Dimensions dimensions,
            Point envelopeOrigin, Dimensions envelopeDimensions, double supportRatio) {
        this(instance, position, rotation, dimensions, envelopeOrigin, envelopeDimensions, supportRatio, Weight.ZERO);
    }

    public Placement(ItemInstance instance, Point position, Rotation rotation, Dimensions dimensions,
            Point envelopeOrigin, Dimensions envelopeDimensions) {
        this(instance, position, rotation, dimensions, envelopeOrigin, envelopeDimensions, 1.0);
    }

    public AxisAlignedBox box() {
        return new AxisAlignedBox(position, dimensions);
    }

    public AxisAlignedBox envelopeBox() {
        return new AxisAlignedBox(envelopeOrigin, envelopeDimensions);
    }

    /**
     * This placement's rotated hull, or empty when its box is the honest answer:
     * empty for every {@code rigid_cuboid}, and for the cases {@link #hullCollisionIsExact}
     * names.
     */
    public Optional<HullShape> hullShape() {
        Item item = instance.item();
        boolean sameEnvelope = envelopeDimensions.equals(dimensions);
        if (!hullCollisionIsExact(item, sameEnvelope)) {
            return Optional.empty();
        }
        return Optional.of(HullShape.shapeFor(item.hullVertices().orElseThrow(), rotation));
    }

    /**
     * Whether this item's collisions may be decided by its hull rather than by its box.
     *
     * <p>Three conditions, each falling back to the box for its own reason: it is not a
     * {@code convex_hull}; a clearance has inflated the envelope past the physical box, and a
     * margin around a hull is not a hull; or the item is on a route, where the sequence replay
     * reasons with box sweeps only and packing tighter than it can verify would produce
     * arrangements the engine then reports as unloadable. Every fallback over-reserves space,
     * the only safe direction to be wrong in.
     */
    public static boolean hullCollisionIsExact(Item item, boolean envelopeMatchesPhysical) {
        return item.shapeType() == ShapeType.CONVEX_HULL && envelopeMatchesPhysical && item.stopIndex().isEmpty();
    }

    /**
     * Do two placed items actually overlap?
     *
     * <p>The axis-aligned envelope test is the broad phase and stays mandatory; this refines
     * its answer only when a hull is one of the two solids. One definition, so the solver, the
     * sequence check and the final validation cannot disagree about what "collides" means.
     */
    public static boolean collide(Placement left, Placement right) {
        AxisAlignedBox leftBox = left.envelopeBox();
        AxisAlignedBox rightBox = right.envelopeBox();
        if (!leftBox.intersects(rightBox)) {
            return false;
        }
        Optional<HullShape> leftShape = left.hullShape();
        Optional<HullShape> rightShape = right.hullShape();
        if (leftShape.isEmpty() && rightShape.isEmpty()) {
            return true;
        }
        return HullShape.collide(leftShape.orElseGet(() -> boxShape(leftBox)), corner(leftBox),
                rightShape.orElseGet(() -> boxShape(rightBox)), corner(rightBox));
    }

    /** Whether a placed item overlaps a plain box -- an obstacle, or any other fixed solid. */
    public static boolean hitsBox(Placement placement, AxisAlignedBox box) {
        AxisAlignedBox envelope = placement.envelopeBox();
        if (!envelope.intersects(box)) {
            return false;
        }
        Optional<HullShape> shape = placement.hullShape();
        if (shape.isEmpty()) {
            return true;
        }
        return HullShape.collide(shape.get(), corner(envelope), boxShape(box), corner(box));
    }

    private static long[] corner(AxisAlignedBox box) {
        return new long[] {box.origin().x(), box.origin().y(), box.origin().z()};
    }

    private static HullShape boxShape(AxisAlignedBox box) {
        return HullShape.box(box.dimensions().length().ticks(), box.dimensions().width().ticks(),
                box.dimensions().height().ticks());
    }
}
