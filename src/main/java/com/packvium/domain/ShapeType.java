package com.packvium.domain;

/**
 * How much of an item's declared box the item actually occupies.
 *
 * <p>{@code RIGID_CUBOID} is the default: the item is its box. The other two narrow that in one
 * dimension each -- {@code CONVEX_HULL} in space, {@code COMPRESSIBLE} in height under load --
 * and neither may be inferred. An engine that packed a hull as its bounding box would return a
 * plan that validates and does not physically fit.
 */
public enum ShapeType {
    RIGID_CUBOID("rigid_cuboid"),
    CONVEX_HULL("convex_hull"),
    COMPRESSIBLE("compressible");

    private final String wireName;

    ShapeType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ShapeType fromWireName(String wireName) {
        for (ShapeType shape : values()) {
            if (shape.wireName.equals(wireName)) {
                return shape;
            }
        }
        throw new IllegalArgumentException("Unsupported shape_type: " + wireName);
    }
}
