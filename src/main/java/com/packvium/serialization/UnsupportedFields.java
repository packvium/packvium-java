package com.packvium.serialization;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Public request fields this engine does not implement yet, by the scope they appear in.
 *
 * <p>The decoder reads the keys it knows and ignores the rest, so without this guard an
 * unimplemented field would produce an answer computed as though the caller had never sent
 * it. Every name here leaves the list in the change that implements it.
 */
public record UnsupportedFields(
        List<String> request,
        List<String> configuration,
        List<String> item,
        List<String> container,
        List<String> shapeTypes) {

    /**
     * What this engine still refuses. {@code pallet_overhang_limit} was reserved in the schema at
     * the 1.1.0 contract freeze and is refused until an engine implements it from a request: a
     * field a caller can set and the solver ignores is worse than a refusal.
     */
    public static final UnsupportedFields CURRENT = new UnsupportedFields(
            List.of(), List.of(), List.of(), List.of("pallet_overhang_limit"), List.of());

    public UnsupportedFields {
        request = List.copyOf(request);
        configuration = List.copyOf(configuration);
        item = List.copyOf(item);
        container = List.copyOf(container);
        shapeTypes = List.copyOf(shapeTypes);
    }

    /** Refuses the request naming every unimplemented field once, in sorted order. O(fields x entries). */
    public void reject(Map<?, ?> request) {
        TreeSet<String> found = new TreeSet<>();
        for (String key : this.request) {
            if (request.containsKey(key)) {
                found.add(key);
            }
        }
        if (request.get("configuration") instanceof Map<?, ?> config) {
            configuration.stream().filter(config::containsKey).forEach(key -> found.add("configuration." + key));
        }
        collect(request.get("items"), "item", item, found);
        collect(request.get("containers"), "container", container, found);
        collectShapeTypes(request.get("items"), found);
        if (found.isEmpty()) {
            return;
        }
        throw new UnsupportedFeatureException("unsupported_feature: the Java engine does not yet implement "
                + String.join(", ", found) + "; the request was rejected instead of silently ignoring public fields");
    }

    private static void collect(Object entries, String scope, List<String> keys, TreeSet<String> found) {
        if (!(entries instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> object) {
                keys.stream().filter(object::containsKey).forEach(key -> found.add(scope + "." + key));
            }
        }
    }

    private void collectShapeTypes(Object entries, TreeSet<String> found) {
        if (!(entries instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> object && object.get("shape_type") instanceof String shape
                    && shapeTypes.contains(shape)) {
                found.add("item.shape_type=" + shape);
            }
        }
    }
}
