package com.packvium.serialization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The catalog versions a request was resolved against, echoed unchanged once checked. */
final class CatalogVersions {
    private static final Set<String> REQUIRED = new TreeSet<>(List.of("catalog_id", "effective_at", "resolved_at", "version"));

    private CatalogVersions() {
    }

    static List<Map<String, Object>> read(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("catalog_versions_used must be an array");
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> references = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            references.add(reference(entries.get(index), index, seen));
        }
        return references;
    }

    private static Map<String, Object> reference(Object entry, int index, Set<String> seen) {
        String at = "catalog_versions_used[" + index + "]";
        if (!(entry instanceof Map<?, ?> reference)) {
            throw new IllegalArgumentException(at + " must be an object");
        }
        Set<String> keys = new TreeSet<>();
        reference.keySet().forEach(key -> keys.add(key.toString()));
        if (!keys.equals(REQUIRED)) {
            throw new IllegalArgumentException(at + " must contain exactly the canonical fields");
        }
        if (!(reference.get("catalog_id") instanceof String catalogId) || catalogId.isEmpty()) {
            throw new IllegalArgumentException(at + ".catalog_id must be non-empty");
        }
        if (!seen.add(catalogId)) {
            throw new IllegalArgumentException("catalog_versions_used contains ambiguous duplicate '" + catalogId + "'");
        }
        requireAtLeast(reference, "version", 1, at);
        requireAtLeast(reference, "effective_at", 0, at);
        requireAtLeast(reference, "resolved_at", 0, at);
        @SuppressWarnings("unchecked") // JsonReader only ever builds String-keyed objects.
        Map<String, Object> typed = (Map<String, Object>) reference;
        return typed;
    }

    private static void requireAtLeast(Map<?, ?> reference, String field, long minimum, String at) {
        if (!(reference.get(field) instanceof Long value) || value < minimum) {
            throw new IllegalArgumentException(at + "." + field + " must be >= " + minimum);
        }
    }
}
