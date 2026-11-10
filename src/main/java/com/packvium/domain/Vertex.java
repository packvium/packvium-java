package com.packvium.domain;

/** One hull vertex in the item's own frame, in length ticks; may be negative. */
public record Vertex(long x, long y, long z) {
    public long component(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2");
        };
    }

    public Vertex minus(Vertex other) {
        return new Vertex(x - other.x, y - other.y, z - other.z);
    }
}
