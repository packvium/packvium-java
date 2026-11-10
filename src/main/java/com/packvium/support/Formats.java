package com.packvium.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fixed-point renderings of doubles that match C's printf on the exact binary value. */
public final class Formats {
    private Formats() {
    }

    /**
     * {@code sprintf("%.6f", value)}. Java's own {@code %.6f} rounds the shortest decimal
     * spelling of the double rather than its exact value, so it can disagree in the last digit.
     */
    public static String fixed6(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("cannot render " + value + " as a fixed decimal");
        }
        return new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }
}
