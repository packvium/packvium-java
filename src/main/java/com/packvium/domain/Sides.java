package com.packvium.domain;

/** Three non-negative tick counts along length, width and height: an extent or a box size. */
public record Sides(long length, long width, long height) {
    public static Sides of(Dimensions dimensions) {
        return new Sides(dimensions.length().ticks(), dimensions.width().ticks(), dimensions.height().ticks());
    }

    public long get(int axis) {
        return switch (axis) {
            case 0 -> length;
            case 1 -> width;
            case 2 -> height;
            default -> throw new IllegalArgumentException("axis " + axis);
        };
    }

    /** As dimensions; every side must be positive, as a packed container's extent always is. */
    public Dimensions toDimensions() {
        return Dimensions.ofTicks(length, width, height);
    }
}
