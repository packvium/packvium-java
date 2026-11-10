package com.packvium.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

public record PackingRequest(List<Item> items, List<Container> containers) {
    public PackingRequest {
        items = List.copyOf(items);
        containers = List.copyOf(containers);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("At least one item is required");
        }
        if (containers.isEmpty()) {
            throw new IllegalArgumentException("At least one container is required");
        }
        requireUnique(items, Item::id, "Item");
        requireUnique(containers, Container::id, "Container");
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
                throw new IllegalArgumentException(kind + " ids must be unique");
            }
        }
    }
}
