package com.packvium.validation;

import java.math.BigInteger;

/** A point or a direction in ticks, exact. */
record Vertex(BigInteger x, BigInteger y, BigInteger z) implements Comparable<Vertex> {
    static final Vertex ZERO = new Vertex(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);

    BigInteger component(int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    Vertex minus(Vertex other) {
        return new Vertex(x.subtract(other.x), y.subtract(other.y), z.subtract(other.z));
    }

    Vertex plus(Vertex other) {
        return new Vertex(x.add(other.x), y.add(other.y), z.add(other.z));
    }

    Vertex negate() {
        return new Vertex(x.negate(), y.negate(), z.negate());
    }

    Vertex cross(Vertex other) {
        return new Vertex(y.multiply(other.z).subtract(z.multiply(other.y)),
                z.multiply(other.x).subtract(x.multiply(other.z)),
                x.multiply(other.y).subtract(y.multiply(other.x)));
    }

    BigInteger dot(Vertex other) {
        return x.multiply(other.x).add(y.multiply(other.y)).add(z.multiply(other.z));
    }

    /** Python's tuple order: x, then y, then z. */
    @Override
    public int compareTo(Vertex other) {
        int order = x.compareTo(other.x);
        if (order == 0) {
            order = y.compareTo(other.y);
        }
        return order != 0 ? order : z.compareTo(other.z);
    }
}
