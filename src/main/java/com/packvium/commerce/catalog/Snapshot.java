package com.packvium.commerce.catalog;

import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/**
 * The complete, immutable content of one catalog version. Immutability is what makes
 * concurrent publication safe: a snapshot already handed out can never be reached back
 * into by a later publish().
 */
public record Snapshot(
        List<ItemMaster> items,
        List<CartonMaster> cartons,
        List<PalletMaster> pallets,
        List<ExclusionRule> exclusions,
        List<FacilityOverride> overrides) {
    public Snapshot {
        items = List.copyOf(items);
        cartons = List.copyOf(cartons);
        pallets = List.copyOf(pallets);
        exclusions = List.copyOf(exclusions);
        overrides = List.copyOf(overrides);
        requireUniqueIds("item", items, ItemMaster::id);
        requireUniqueIds("carton", cartons, CartonMaster::id);
        requireUniqueIds("pallet", pallets, PalletMaster::id);
        requireUniqueIds("exclusion", exclusions, ExclusionRule::id);
        requireUniqueIds("facility override", overrides, FacilityOverride::id);
    }

    public Snapshot() {
        this(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Item ids, sorted ascending by code point. */
    public List<String> itemIds() {
        return sortedIds(items, ItemMaster::id);
    }

    /** Carton ids, sorted ascending by code point. */
    public List<String> cartonIds() {
        return sortedIds(cartons, CartonMaster::id);
    }

    /** Pallet ids, sorted ascending by code point. */
    public List<String> palletIds() {
        return sortedIds(pallets, PalletMaster::id);
    }

    private static <T> List<String> sortedIds(List<T> entries, Function<T, String> id) {
        List<String> ids = new ArrayList<>(entries.size());
        for (T entry : entries) {
            ids.add(id.apply(entry));
        }
        ids.sort(SortKey::compareCodePoints);
        return List.copyOf(ids);
    }

    private static <T> void requireUniqueIds(String label, List<T> entries, Function<T, String> id) {
        // Membership only; the set's iteration order never reaches output.
        HashSet<String> seen = new HashSet<>();
        for (T entry : entries) {
            if (!seen.add(id.apply(entry))) {
                throw new IllegalArgumentException("duplicate " + label + " ids in catalog snapshot");
            }
        }
    }
}
