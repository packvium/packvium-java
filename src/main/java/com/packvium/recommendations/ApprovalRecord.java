package com.packvium.recommendations;

/**
 * The immutable record of one approval: which recommendation, when, and which registry version
 * publishing it produced.
 *
 * @param recommendationId the approved recommendation
 * @param approvedAt when it was approved, in the caller's epoch milliseconds
 * @param publishedVersion the version number the registry assigned
 */
public record ApprovalRecord(String recommendationId, long approvedAt, long publishedVersion) {
    /** Refuses an unnamed recommendation, a negative time and a non-positive version. */
    public ApprovalRecord {
        if (recommendationId == null || recommendationId.isEmpty()) {
            throw new IllegalArgumentException("recommendation_id is required");
        }
        if (approvedAt < 0) {
            throw new IllegalArgumentException("approved_at cannot be negative");
        }
        if (publishedVersion <= 0) {
            throw new IllegalArgumentException("published_version must be positive");
        }
    }
}
