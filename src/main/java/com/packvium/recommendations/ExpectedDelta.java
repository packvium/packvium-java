package com.packvium.recommendations;

/**
 * One metric's change from baseline to treatment, averaged over every order both scenarios
 * succeeded on. An order only one arm could pack contributes to neither mean.
 *
 * @param metric the metric's name
 * @param baselineMean the baseline arm's mean over the paired orders
 * @param treatmentMean the treatment arm's mean over the same orders
 */
public record ExpectedDelta(String metric, double baselineMean, double treatmentMean) {
    /** Refuses an unnamed metric. */
    public ExpectedDelta {
        if (metric == null || metric.isEmpty()) {
            throw new IllegalArgumentException("metric is required");
        }
    }

    /**
     * Treatment minus baseline. Signed on purpose: whether a negative delta is good depends on the
     * axis, and this type deliberately does not know.
     */
    public double delta() {
        return treatmentMean - baselineMean;
    }
}
