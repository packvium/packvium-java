package com.packvium.holdout;

/** What the replay concluded about one held-out decision. */
public enum Verdict {
    /** The treatment won the decision's Pareto report, or packed what the baseline could not. */
    IMPROVED("improved"),
    /** The baseline won, or the treatment failed or was rejected by the validator. */
    REGRESSED("regressed"),
    /** Both arms are valid and neither dominates: a real trade-off, or no difference. */
    TRADED_OFF("traded_off"),
    /** Neither arm produced a packing the validator accepted. */
    UNPACKABLE("unpackable");

    private final String value;

    Verdict(String value) {
        this.value = value;
    }

    /** The reference spelling, such as {@code traded_off}. */
    public String value() {
        return value;
    }
}
