package com.packvium.holdout;

/**
 * The recommendation cites a decision the split holds out. Refused rather than scored: a
 * recommendation evaluated on the evidence it was derived from measures how well it remembers,
 * not whether it generalises.
 */
public final class SupportingEvidenceInHoldoutError extends HoldoutError {
    private static final long serialVersionUID = 1L;

    /** @param message names the recommendation and the cited held-out decisions */
    public SupportingEvidenceInHoldoutError(String message) {
        super(message);
    }
}
