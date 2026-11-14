package com.packvium.holdout;

import java.util.List;

/**
 * An independent validator's answer about one packing.
 *
 * <p>Deliberately carries no score and no metric. It is produced by an
 * {@link IndependentValidator} that is never handed one, which is what keeps a learned number
 * from reaching the verdict.
 *
 * @param valid whether the packing passed
 * @param codes the rules a rejected packing failed; empty for a valid one
 */
public record ValidationVerdict(boolean valid, List<String> codes) {
    /** Refuses a valid verdict with codes and a rejection without them. */
    public ValidationVerdict {
        codes = List.copyOf(codes);
        if (valid && !codes.isEmpty()) {
            throw new IllegalArgumentException("a valid verdict cannot carry rejection codes");
        }
        if (!valid && codes.isEmpty()) {
            throw new IllegalArgumentException("a rejection must say which rules it failed");
        }
    }

    /** A packing that passed. */
    public static ValidationVerdict accepted() {
        return new ValidationVerdict(true, List.of());
    }

    /** A packing that failed the named rules. */
    public static ValidationVerdict rejected(List<String> codes) {
        return new ValidationVerdict(false, codes);
    }
}
