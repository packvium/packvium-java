package com.packvium.constraint;

import com.packvium.domain.Container;
import java.math.BigInteger;

/**
 * Volume set aside for packing material, exact even for a metre-scale container.
 *
 * <p>Not floating-point {@code ratio * volume}: a one-metre container's volume (4.096e21 cubic
 * ticks) is far beyond the range where a double is exact, and this is a feasibility number,
 * not a display one.
 */
public final class VolumeReserve {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);

    private VolumeReserve() {
    }

    public static BigInteger reserved(Container container) {
        BigInteger scaled = BigInteger.valueOf(SupportConstraint.scaled(container.voidFillReserveRatio()));
        return container.innerDimensions().volume().multiply(scaled).divide(MILLION);
    }

    public static BigInteger usable(Container container) {
        return container.innerDimensions().volume().subtract(reserved(container));
    }
}
