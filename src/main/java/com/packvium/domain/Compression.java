package com.packvium.domain;

import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.math.BigInteger;

/**
 * Occupied height of a {@code compressible} item under the load resting on it.
 *
 * <p>Pressure is an exact reduced rational carried as a numerator and a denominator rather
 * than a float: the crush limit is a comparison, which cross multiplication answers without
 * dividing at all, and the boundary is inclusive, so a value one part in a million over the
 * limit has to land on the right side of it. The numerator reaches
 * {@code load * 980665 * 16000000^2}, far past a long, so it is a {@link BigInteger}.
 */
public final class Compression {
    /** Parts per million: compression_ratio is carried as an exact integer share of one. */
    public static final long PPM = 1_000_000;

    /** Conventional standard gravity, exactly 9.80665 m/s^2, as a rational. */
    private static final long GRAVITY_NUMERATOR = 980_665;
    private static final long GRAVITY_DENOMINATOR = 100_000;

    private static final long PASCALS_PER_KILOPASCAL = 1000;

    private Compression() {
    }

    /** An exact pressure in kPa as {@code numerator / denominator}, both non-negative. */
    public record Pressure(BigInteger numerator, BigInteger denominator) {
    }

    /**
     * Exact pressure in kPa from the cumulative mass above an item, over its footprint. The
     * item's own mass is excluded -- it is not a load on itself -- and the footprint is the
     * uncompressed one, which compression never changes.
     */
    public static Pressure pressure(long topLoadTicks, long footprintAreaTicks) {
        if (footprintAreaTicks <= 0) {
            throw new IllegalArgumentException("footprint area must be positive");
        }
        BigInteger metre = BigInteger.valueOf(Length.TICKS_PER_MM * 1000);
        BigInteger numerator = big(topLoadTicks).multiply(big(GRAVITY_NUMERATOR)).multiply(metre.multiply(metre));
        BigInteger denominator = big(Weight.TICKS_PER_KG).multiply(big(GRAVITY_DENOMINATOR))
                .multiply(big(PASCALS_PER_KILOPASCAL).multiply(big(footprintAreaTicks)));
        return new Pressure(numerator, denominator);
    }

    /**
     * Is the applied pressure strictly above the declared limit? Cross multiplication, so the
     * comparison never leaves exact integer arithmetic.
     */
    public static boolean exceeds(Pressure pressure, long limitKilopascals) {
        BigInteger ceiling = big(limitKilopascals).multiply(pressure.denominator());
        return pressure.numerator().compareTo(ceiling) > 0;
    }

    /**
     * Occupied height under load, rounded up, never below one tick.
     *
     * <p>Rounding up keeps a discrete packer honest: it may never claim less space than the
     * continuous model allows. The one-tick floor stops a fully compressible item reaching
     * zero height, where it would slip past collision and support invariants entirely.
     */
    public static long effectiveHeight(long heightTicks, long ratioPpm, long limitKilopascals, Pressure pressure) {
        if (heightTicks <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        if (ratioPpm < 0 || ratioPpm > PPM) {
            throw new IllegalArgumentException("compression ratio must be between zero and one");
        }
        if (limitKilopascals < 0) {
            throw new IllegalArgumentException("maximum pressure cannot be negative");
        }
        // With no headroom declared the only admissible pressure is zero, so the item is
        // simply uncompressed. Returning here also keeps the divisor below non-zero.
        if (limitKilopascals == 0) {
            return heightTicks;
        }
        BigInteger divisor = big(limitKilopascals).multiply(big(PPM)).multiply(pressure.denominator());
        // Non-negative: the crush guard bounds the numerator by `limit * denominator` and the
        // ratio by PPM, so the product cannot exceed the divisor.
        BigInteger retained = divisor.subtract(big(ratioPpm).multiply(pressure.numerator()));
        BigInteger scaled = big(heightTicks).multiply(retained);
        BigInteger rounded = scaled.add(divisor.subtract(BigInteger.ONE)).divide(divisor);
        return Math.max(1, CentreOfMass.saturatingLong(rounded));
    }

    /**
     * The published ratio rule, {@code floor(ratio * 1000000 + 0.5)}, applied once at the
     * boundary, so the float a caller supplied never reaches the geometry.
     */
    public static long ratioToPpm(double ratio) {
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("compression_ratio must be between zero and one");
        }
        return (long) (ratio * PPM + 0.5);
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
