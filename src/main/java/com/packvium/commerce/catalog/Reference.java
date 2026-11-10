package com.packvium.commerce.catalog;

/** A pinned pointer to one concrete, immutable catalog version. */
public record Reference(String catalogId, long version, long effectiveAt, long resolvedAt) {
    public Reference {
        if (catalogId.isEmpty()) {
            throw new IllegalArgumentException("catalog_id is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (effectiveAt < 0) {
            throw new IllegalArgumentException("effective_at cannot be negative");
        }
        if (resolvedAt < 0) {
            throw new IllegalArgumentException("resolved_at cannot be negative");
        }
    }
}
