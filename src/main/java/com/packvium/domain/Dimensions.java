package com.packvium.domain;

import com.packvium.unit.Length;
import com.packvium.unit.Rounding;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record Dimensions(Length length, Length width, Length height) {
    public Dimensions {
        if (Math.min(length.ticks(), Math.min(width.ticks(), height.ticks())) <= 0) {
            throw new IllegalArgumentException("All dimensions must be positive");
        }
    }

    public static Dimensions of(String length, String width, String height, String unit) {
        return new Dimensions(
                Length.of(length, unit, Rounding.NEAREST),
                Length.of(width, unit, Rounding.NEAREST),
                Length.of(height, unit, Rounding.NEAREST));
    }

    public static Dimensions mm(long length, long width, long height) {
        return new Dimensions(Length.mm(length), Length.mm(width), Length.mm(height));
    }

    /** Decimal or fractional millimetres, e.g. {@code "12.5"} or {@code "3/16"}. */
    public static Dimensions mm(String length, String width, String height) {
        return of(length, width, height, "mm");
    }

    /** Decimal or fractional inches, e.g. {@code "12 3/8"}; exact, because an inch is a whole tick count. */
    public static Dimensions inches(String length, String width, String height) {
        return of(length, width, height, "in");
    }

    public static Dimensions ofTicks(long length, long width, long height) {
        return new Dimensions(new Length(length), new Length(width), new Length(height));
    }

    /** Reads {length, width, height}, each in any request spelling of a length. */
    public static Dimensions fromMap(Map<?, ?> data, String unit, Rounding rounding) {
        return new Dimensions(
                Length.parse(data.get("length"), unit, rounding),
                Length.parse(data.get("width"), unit, rounding),
                Length.parse(data.get("height"), unit, rounding));
    }

    public Dimensions rotated(Rotation rotation) {
        return switch (rotation) {
            case LWH -> this;
            case LHW -> new Dimensions(length, height, width);
            case WLH -> new Dimensions(width, length, height);
            case WHL -> new Dimensions(width, height, length);
            case HLW -> new Dimensions(height, length, width);
            case HWL -> new Dimensions(height, width, length);
        };
    }

    /** The allowed rotations that give distinct boxes, first rotation winning, in input order. */
    public List<OrientedDimensions> uniqueRotations(List<Rotation> allowed) {
        Set<Dimensions> seen = new HashSet<>();
        List<OrientedDimensions> distinct = new ArrayList<>();
        for (Rotation rotation : allowed) {
            Dimensions rotated = rotated(rotation);
            if (seen.add(rotated)) {
                distinct.add(new OrientedDimensions(rotation, rotated));
            }
        }
        return List.copyOf(distinct);
    }

    public boolean fitsInside(Dimensions other) {
        return length.ticks() <= other.length.ticks()
                && width.ticks() <= other.width.ticks()
                && height.ticks() <= other.height.ticks();
    }

    /** Grows every edge by {@code clearance} on both sides. */
    public Dimensions expand(Length clearance) {
        long both = Math.multiplyExact(clearance.ticks(), 2);
        return ofTicks(
                Math.addExact(length.ticks(), both),
                Math.addExact(width.ticks(), both),
                Math.addExact(height.ticks(), both));
    }

    /** Exact volume in cubic ticks; it overflows a long long before any real container does. */
    public BigInteger volume() {
        return BigInteger.valueOf(length.ticks())
                .multiply(BigInteger.valueOf(width.ticks()))
                .multiply(BigInteger.valueOf(height.ticks()));
    }

    public long baseAreaTicks() {
        return Math.multiplyExact(length.ticks(), width.ticks());
    }

    public long maxEdge() {
        return Math.max(length.ticks(), Math.max(width.ticks(), height.ticks()));
    }

    /**
     * The carrier-style volumetric weight of a box: (L * W * H in lengthUnit) / divisor.
     *
     * <p>{@code divisor} is a carrier's published constant (commonly 139 or 166 for inches/lb,
     * 5000 or 6000 for cm/kg); it already encodes the length/weight convention, so the caller
     * names both explicitly rather than the library guessing one. The quotient is truncated.
     */
    public Weight dimensionalWeight(long divisor, String lengthUnit, String weightUnit) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor must be positive");
        }
        BigInteger lengthTicksPerUnit = BigInteger.valueOf(Length.multiplier(lengthUnit));
        BigInteger numerator = volume().multiply(BigInteger.valueOf(Weight.multiplier(weightUnit)));
        BigInteger denominator = lengthTicksPerUnit.pow(3).multiply(BigInteger.valueOf(divisor));
        return new Weight(numerator.divide(denominator).longValueExact());
    }

    public Map<String, Object> toMap(String unit) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("length", length.toMap(unit));
        rendered.put("width", width.toMap(unit));
        rendered.put("height", height.toMap(unit));
        return rendered;
    }
}
