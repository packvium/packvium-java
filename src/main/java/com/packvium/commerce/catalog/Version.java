package com.packvium.commerce.catalog;

import java.util.Optional;

/**
 * One immutable, numbered entry in a catalog's append-only publication history.
 * A rollback is not a mutation: it is a new, higher-numbered version whose snapshot
 * equals a prior one's, recorded via rolledBackFrom.
 */
public record Version(long number, Snapshot snapshot, long effectiveAt, long publishedAt, Optional<Long> rolledBackFrom,
        String note) {
    public Version {
        if (number <= 0) {
            throw new IllegalArgumentException("version number must be positive");
        }
        if (effectiveAt < 0) {
            throw new IllegalArgumentException("effective_at cannot be negative");
        }
        if (publishedAt < 0) {
            throw new IllegalArgumentException("published_at cannot be negative");
        }
        if (rolledBackFrom.isPresent() && rolledBackFrom.get() <= 0) {
            throw new IllegalArgumentException("rolled_back_from must reference a positive version number");
        }
    }

    public Version(long number, Snapshot snapshot, long effectiveAt, long publishedAt) {
        this(number, snapshot, effectiveAt, publishedAt, Optional.empty(), "");
    }
}
