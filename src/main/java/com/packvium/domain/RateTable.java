package com.packvium.domain;

import java.util.List;
import java.util.OptionalLong;

/**
 * A carrier rate card carried by the request.
 *
 * <p>The price charged is the one at the first bracket at or above the billed weight, which
 * is how a carrier's own published table reads. Everything is an exact integer -- grams, minor
 * currency units, parts per thousand -- because a landed cost that participates in the
 * objective must be reproducible bit for bit across engines. Prices need not be
 * non-decreasing: a promotional band that dips is a real rate card.
 */
public record RateTable(
        List<Long> weightBracketsG,
        List<Long> pricesMinor,
        long minimumChargeMinor,
        long fuelSurchargePermille) {
    public RateTable {
        weightBracketsG = List.copyOf(weightBracketsG);
        pricesMinor = List.copyOf(pricesMinor);
        if (weightBracketsG.isEmpty()) {
            throw new IllegalArgumentException("rate_table requires at least one weight bracket");
        }
        if (weightBracketsG.size() != pricesMinor.size()) {
            throw new IllegalArgumentException(
                    "rate_table weight_brackets_g and prices_minor must be the same length");
        }
        requireAscending(weightBracketsG);
        for (long price : pricesMinor) {
            if (price < 0) {
                throw new IllegalArgumentException("rate_table prices cannot be negative");
            }
        }
        if (minimumChargeMinor < 0) {
            throw new IllegalArgumentException("rate_table minimum_charge_minor cannot be negative");
        }
        if (fuelSurchargePermille < 0) {
            throw new IllegalArgumentException("rate_table fuel_surcharge_permille cannot be negative");
        }
    }

    /** A rate card with no minimum charge and no fuel surcharge. */
    public RateTable(List<Long> weightBracketsG, List<Long> pricesMinor) {
        this(weightBracketsG, pricesMinor, 0, 0);
    }

    /**
     * The exact landed cost at this billed weight, or empty when the tariff does not price it.
     *
     * <p>The search must compare an unpriceable candidate rather than abort on one, so this
     * does not throw; {@link #chargeMinor} is the same walk for callers who want the refusal.
     * O(brackets).
     */
    public OptionalLong chargeMinorOrEmpty(long billedWeightG) {
        for (int index = 0; index < weightBracketsG.size(); index++) {
            if (billedWeightG <= weightBracketsG.get(index)) {
                long base = Math.max(pricesMinor.get(index), minimumChargeMinor);
                // Ceiling division: rounding down would let a fractional unit of revenue vanish.
                long surcharge = Math.addExact(Math.multiplyExact(base, fuelSurchargePermille), 999) / 1000;
                return OptionalLong.of(Math.addExact(base, surcharge));
            }
        }
        return OptionalLong.empty();
    }

    /** Throws above the last bracket rather than clamping, which would under-price oversize loads. */
    public long chargeMinor(long billedWeightG) {
        OptionalLong charge = chargeMinorOrEmpty(billedWeightG);
        if (charge.isEmpty()) {
            throw new UnratedWeightException("billed weight " + billedWeightG
                    + " g is above the rate table's last bracket (" + lastBracketG()
                    + " g); the shipment has no published price");
        }
        return charge.getAsLong();
    }

    public long lastBracketG() {
        return weightBracketsG.get(weightBracketsG.size() - 1);
    }

    private static void requireAscending(List<Long> brackets) {
        long previous = 0;
        for (long bound : brackets) {
            if (bound <= 0) {
                throw new IllegalArgumentException("rate_table weight brackets must be positive");
            }
            if (bound <= previous) {
                throw new IllegalArgumentException("rate_table weight brackets must be strictly ascending");
            }
            previous = bound;
        }
    }
}
