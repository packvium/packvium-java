package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** An axis-aligned box in ticks; its far corner and base area are computed once. */
final class Cuboid {
    final BigInteger x;
    final BigInteger y;
    final BigInteger z;
    final BigInteger length;
    final BigInteger width;
    final BigInteger height;
    final BigInteger x2;
    final BigInteger y2;
    final BigInteger z2;
    final BigInteger baseArea;

    Cuboid(BigInteger x, BigInteger y, BigInteger z, BigInteger length, BigInteger width, BigInteger height) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.length = length;
        this.width = width;
        this.height = height;
        this.x2 = x.add(length);
        this.y2 = y.add(width);
        this.z2 = z.add(height);
        this.baseArea = length.multiply(width);
    }

    boolean intersects(Cuboid other) {
        return x.compareTo(other.x2) < 0 && other.x.compareTo(x2) < 0
                && y.compareTo(other.y2) < 0 && other.y.compareTo(y2) < 0
                && z.compareTo(other.z2) < 0 && other.z.compareTo(z2) < 0;
    }

    boolean contains(Cuboid other) {
        return x.compareTo(other.x) <= 0 && y.compareTo(other.y) <= 0 && z.compareTo(other.z) <= 0
                && other.x2.compareTo(x2) <= 0 && other.y2.compareTo(y2) <= 0 && other.z2.compareTo(z2) <= 0;
    }

    BigInteger overlapAreaXy(Cuboid other) {
        BigInteger dx = x2.min(other.x2).subtract(x.max(other.x)).max(BigInteger.ZERO);
        BigInteger dy = y2.min(other.y2).subtract(y.max(other.y)).max(BigInteger.ZERO);
        return dx.multiply(dy);
    }

    /** The eight corners, in the reference's order. */
    List<Vertex> vertices() {
        List<Vertex> corners = new ArrayList<>(8);
        for (BigInteger cornerX : List.of(x, x2)) {
            for (BigInteger cornerY : List.of(y, y2)) {
                for (BigInteger cornerZ : List.of(z, z2)) {
                    corners.add(new Vertex(cornerX, cornerY, cornerZ));
                }
            }
        }
        return corners;
    }
}
