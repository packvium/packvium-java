package com.packvium.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A made-to-measure container's machine.
 *
 * <p>The container's declared inner dimensions are the largest box the machine can cut; this
 * adds the smallest size and the cut step per axis, in ticks, and the board prices. A trial packs
 * the round's items into up to four solving envelopes ({@link #solvingEnvelopes}) and keeps the
 * first that places every item; the committed box is the achieved extent rounded up onto the
 * machine grid ({@link Container#fabricate}). Areas, volumes and products are exact
 * {@code BigInteger}s, so this port derives the same rungs, box and price as the Python engine.
 */
public record MadeToMeasure(Sides minimum, Sides increment, long costPerSquareMetreMinor, long tarePerSquareMetre,
        Sides outerAllowance) {

    /** One length tick is 1/16 000 000 m, so 1 m^2 is this many square ticks. */
    public static final BigInteger SQUARE_METRE_TICKS2 = BigInteger.valueOf(16_000_000L * 16_000_000L);
    /** The largest integer every binding returns unchanged; no fabricated price may pass it. */
    public static final BigInteger MAX_EXACT_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    /** Each compact rung's side as a fraction of the integer cube root of the round's volume. */
    private static final long[][] RUNG_FACTORS = {{5, 4}, {3, 2}, {2, 1}};
    private static final List<String> AXES = List.of("length", "width", "height");

    public MadeToMeasure {
        if (Math.min(minimum.length(), Math.min(minimum.width(), minimum.height())) <= 0) {
            throw new IllegalArgumentException("made_to_measure minimum must be positive");
        }
        if (Math.min(increment.length(), Math.min(increment.width(), increment.height())) <= 0) {
            throw new IllegalArgumentException("made_to_measure increment must be positive");
        }
        if (costPerSquareMetreMinor < 0 || tarePerSquareMetre < 0) {
            throw new IllegalArgumentException("made_to_measure prices cannot be negative");
        }
        if (Math.min(outerAllowance.length(), Math.min(outerAllowance.width(), outerAllowance.height())) < 0) {
            throw new IllegalArgumentException("made_to_measure outer_allowance cannot be negative");
        }
    }

    /**
     * Refuse a machine whose grid does not land on its own maximum. O(1). Every reachable size is
     * then unambiguous, and rounding an achieved extent inside the maximum up onto the grid can
     * never pass it.
     */
    public void admits(Sides maximum) {
        for (int axis = 0; axis < 3; axis++) {
            if (minimum.get(axis) > maximum.get(axis)) {
                throw new IllegalArgumentException(
                        "made_to_measure minimum " + AXES.get(axis) + " exceeds inner_dimensions");
            }
            if ((maximum.get(axis) - minimum.get(axis)) % increment.get(axis) != 0) {
                throw new IllegalArgumentException("made_to_measure increment " + AXES.get(axis)
                        + " does not divide inner_dimensions minus minimum");
            }
        }
    }

    /** The smallest cuttable size on {@code axis} that is at least {@code value}. */
    public long grid(int axis, long value) {
        long low = minimum.get(axis);
        long step = increment.get(axis);
        long above = Math.max(value, low) - low;
        return low + (above / step + (above % step != 0 ? 1 : 0)) * step;
    }

    /** {@link #grid}, capped at the machine maximum: sizes a solving envelope, never a box. */
    private long clamp(int axis, BigInteger value, long maximum) {
        // Every value at or past the maximum lands on it, since the grid lands on the maximum.
        if (value.compareTo(BigInteger.valueOf(maximum)) >= 0) {
            return maximum;
        }
        return Math.min(maximum, grid(axis, value.longValueExact()));
    }

    /** The inner surface of a closed box, 2(LW + LH + WH), in square ticks. */
    public static BigInteger surfaceArea(Sides sides) {
        BigInteger length = BigInteger.valueOf(sides.length());
        BigInteger width = BigInteger.valueOf(sides.width());
        BigInteger height = BigInteger.valueOf(sides.height());
        return length.multiply(width).add(length.multiply(height)).add(width.multiply(height))
                .shiftLeft(1);
    }

    /** ceil(rate * area / 1 m^2), as {@code p*q + ceil(p*r / T^2)} with {@code q, r = divmod(area, T^2)}. */
    public static BigInteger perSquareMetre(long rate, BigInteger area) {
        BigInteger[] whole = area.divideAndRemainder(SQUARE_METRE_TICKS2);
        BigInteger price = BigInteger.valueOf(rate);
        BigInteger[] part = price.multiply(whole[1]).divideAndRemainder(SQUARE_METRE_TICKS2);
        BigInteger rounded = part[1].signum() > 0 ? part[0].add(BigInteger.ONE) : part[0];
        return price.multiply(whole[0]).add(rounded);
    }

    /** A box's price: the container's own fee plus its board at {@code priceMinor} per square metre. */
    public static BigInteger boxCostFor(long priceMinor, long feeMinor, Sides sides) {
        return BigInteger.valueOf(feeMinor).add(perSquareMetre(priceMinor, surfaceArea(sides)));
    }

    /** Exact: the request rules refuse a machine whose largest box costs more than 2^53 - 1. */
    public long boxCostMinor(long feeMinor, Sides sides) {
        return boxCostFor(costPerSquareMetreMinor, feeMinor, sides).longValueExact();
    }

    /**
     * The fabricated box's weight: the declared tare plus its board, rounded up. The request sets
     * no ceiling on it, so a weight past 64-bit ticks is refused here by name.
     */
    public long boxTareTicks(long declaredTare, Sides sides) {
        BigInteger tare = BigInteger.valueOf(declaredTare).add(perSquareMetre(tarePerSquareMetre, surfaceArea(sides)));
        if (tare.bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException("the fabricated box's tare_weight is past the 64-bit tick range");
        }
        return tare.longValue();
    }

    /** The smallest non-negative c with c^3 >= value: doubling, then bisection, O(log value) steps. */
    static BigInteger cubeRootCeiling(BigInteger value) {
        BigInteger high = BigInteger.ONE;
        while (high.pow(3).compareTo(value) < 0) {
            high = high.shiftLeft(1);
        }
        BigInteger low = BigInteger.ZERO;
        while (low.compareTo(high) < 0) {
            BigInteger middle = low.add(high).shiftRight(1);
            if (middle.pow(3).compareTo(value) >= 0) {
                high = middle;
            } else {
                low = middle.add(BigInteger.ONE);
            }
        }
        return low;
    }

    /**
     * The rungs one trial tries, ascending on every axis, the machine maximum last. {@code edges}
     * are each remaining instance's envelope edges. O(r) over the r instances plus the cube root;
     * the list depends only on their multiset.
     */
    public List<Sides> solvingEnvelopes(Sides maximum, List<Sides> edges) {
        BigInteger volume = BigInteger.ZERO;
        long[] floors = new long[3];
        for (Sides instance : edges) {
            volume = volume.add(BigInteger.valueOf(instance.length()).multiply(BigInteger.valueOf(instance.width()))
                    .multiply(BigInteger.valueOf(instance.height())));
            long[] ranked = {instance.length(), instance.width(), instance.height()};
            Arrays.sort(ranked);
            for (int rank = 0; rank < 3; rank++) {
                floors[rank] = Math.max(floors[rank], ranked[2 - rank]);
            }
        }
        BigInteger side = cubeRootCeiling(volume);
        List<Sides> rungs = new ArrayList<>();
        for (long[] factor : RUNG_FACTORS) {
            BigInteger scaled = side.multiply(BigInteger.valueOf(factor[0])).add(BigInteger.valueOf(factor[1] - 1))
                    .divide(BigInteger.valueOf(factor[1]));
            long[] rung = new long[3];
            for (int axis = 0; axis < 3; axis++) {
                rung[axis] = clamp(axis, scaled.max(BigInteger.valueOf(floors[axis])), maximum.get(axis));
            }
            Sides candidate = new Sides(rung[0], rung[1], rung[2]);
            BigInteger capacity = BigInteger.valueOf(rung[0]).multiply(BigInteger.valueOf(rung[1]))
                    .multiply(BigInteger.valueOf(rung[2]));
            if (capacity.compareTo(volume) >= 0 && !rungs.contains(candidate)) {
                rungs.add(candidate);
            }
        }
        if (!rungs.contains(maximum)) {
            rungs.add(maximum);
        }
        return List.copyOf(rungs);
    }

    /** Each instance's envelope edges, the input {@link #solvingEnvelopes} sizes rungs from. */
    public static List<Sides> instanceEdges(List<ItemInstance> items, long clearanceTicks) {
        long grow = Math.multiplyExact(2, clearanceTicks);
        List<Sides> edges = new ArrayList<>(items.size());
        for (ItemInstance item : items) {
            Dimensions size = item.dimensions();
            edges.add(new Sides(Math.addExact(size.length().ticks(), grow), Math.addExact(size.width().ticks(), grow),
                    Math.addExact(size.height().ticks(), grow)));
        }
        return edges;
    }
}
