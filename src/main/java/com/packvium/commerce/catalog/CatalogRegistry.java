package com.packvium.commerce.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-catalog append-only publication history: the one place catalog state can change,
 * and it can only ever append. Complexity: publish O(1), resolve O(h) over the history.
 */
public final class CatalogRegistry {
    private final String catalogId;
    private final List<Version> versions = new ArrayList<>();

    public CatalogRegistry(String catalogId) {
        if (catalogId.isEmpty()) {
            throw new IllegalArgumentException("catalog_id is required");
        }
        this.catalogId = catalogId;
    }

    public String catalogId() {
        return catalogId;
    }

    /** The full append-only history, oldest first. */
    public List<Version> versions() {
        return List.copyOf(versions);
    }

    public Version publish(Snapshot snapshot, long effectiveAt, long publishedAt, String note) {
        Version version = new Version(versions.size() + 1L, snapshot, effectiveAt, publishedAt, Optional.empty(), note);
        versions.add(version);
        return version;
    }

    public Version publish(Snapshot snapshot, long effectiveAt, long publishedAt) {
        return publish(snapshot, effectiveAt, publishedAt, "");
    }

    /** Publish a new version whose snapshot equals a prior version's. History is never rewritten. */
    public Version rollback(long toVersion, long publishedAt, long effectiveAt, String note) {
        return appendRollback(toVersion, publishedAt, effectiveAt, note);
    }

    /** A rollback with no explicit effective time takes effect when it is published. */
    public Version rollback(long toVersion, long publishedAt, String note) {
        return appendRollback(toVersion, publishedAt, publishedAt, note);
    }

    public Version rollback(long toVersion, long publishedAt) {
        return rollback(toVersion, publishedAt, "");
    }

    private Version appendRollback(long toVersion, long publishedAt, long effectiveAt, String note) {
        Version target = version(toVersion);
        Version version = new Version(
                versions.size() + 1L, target.snapshot(), effectiveAt, publishedAt, Optional.of(toVersion),
                note.isEmpty() ? "rollback to version " + toVersion : note);
        versions.add(version);
        return version;
    }

    /** Resolve one concrete version, pinned by number or by the version effective as of a time. */
    public Version resolve(long resolvedAt, Optional<Long> version, Optional<Long> asOf) {
        if (version.isPresent()) {
            return version(version.get());
        }
        if (asOf.isEmpty()) {
            return onlyVersion();
        }
        return effectiveAsOf(asOf.get());
    }

    private Version onlyVersion() {
        if (versions.size() > 1) {
            throw new AmbiguousReferenceException("catalog '" + catalogId + "' has " + versions.size()
                    + " versions; resolving requires an explicit version or as_of");
        }
        if (versions.isEmpty()) {
            throw new VersionNotFoundException("catalog '" + catalogId + "' has no published versions");
        }
        return versions.get(0);
    }

    private Version effectiveAsOf(long asOf) {
        Version winner = null;
        for (Version candidate : versions) {
            if (candidate.effectiveAt() > asOf) {
                continue;
            }
            // Ties in effective_at break on the higher (later-published) number, so a
            // same-instant correction or rollback wins deterministically.
            if (winner == null
                    || candidate.effectiveAt() > winner.effectiveAt()
                    || (candidate.effectiveAt() == winner.effectiveAt() && candidate.number() > winner.number())) {
                winner = candidate;
            }
        }
        if (winner == null) {
            throw new NoEffectiveVersionException(
                    "catalog '" + catalogId + "' has no version effective as of " + asOf);
        }
        return winner;
    }

    public Version version(long number) {
        for (Version version : versions) {
            if (version.number() == number) {
                return version;
            }
        }
        throw new VersionNotFoundException("catalog '" + catalogId + "' has no version " + number);
    }
}
