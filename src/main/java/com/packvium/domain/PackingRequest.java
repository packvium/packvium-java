package com.packvium.domain;

import com.packvium.support.ObjectRefusal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

public record PackingRequest(List<Item> items, List<Container> containers, List<FixedPlacement> fixedPlacements) {
    public PackingRequest {
        items = List.copyOf(items);
        containers = List.copyOf(containers);
        fixedPlacements = List.copyOf(fixedPlacements);
        if (items.isEmpty()) {
            throw new ObjectRefusal("invalid_value", "at least one item is required");
        }
        if (containers.isEmpty()) {
            throw new ObjectRefusal("invalid_value", "at least one container is required");
        }
        requireUnique(items, Item::id, "item");
        requireUnique(containers, Container::id, "container");
    }

    public PackingRequest(List<Item> items, List<Container> containers) {
        this(items, containers, List.of());
    }

    /** Every physical copy, in item order then sequence order. O(total quantity). */
    public List<ItemInstance> instances() {
        List<ItemInstance> all = new ArrayList<>();
        for (Item item : items) {
            all.addAll(item.instances());
        }
        return List.copyOf(all);
    }

    private static <T> void requireUnique(List<T> values, Function<T, String> id, String kind) {
        HashSet<String> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(id.apply(value))) {
                throw new ObjectRefusal("duplicate_id", kind + " ids must be unique");
            }
        }
    }
}
