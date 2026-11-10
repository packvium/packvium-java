package com.packvium.algorithm;

import com.packvium.domain.ItemInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroupBatcher {
    private GroupBatcher() {
    }

    /**
     * Items in a group share a container, so they are offered to a solver atomically. A batch
     * that does not fit is rejected whole and leaves the rest of the order untouched. Each group
     * sits where its first member does. O(n).
     */
    public static List<List<ItemInstance>> batches(List<ItemInstance> items) {
        Map<String, List<ItemInstance>> groups = new LinkedHashMap<>();
        for (ItemInstance item : items) {
            item.item().group().ifPresent(group -> groups.computeIfAbsent(group, key -> new ArrayList<>()).add(item));
        }
        List<List<ItemInstance>> batches = new ArrayList<>();
        for (ItemInstance item : items) {
            if (item.item().group().isEmpty()) {
                batches.add(List.of(item));
                continue;
            }
            List<ItemInstance> group = groups.remove(item.item().group().get());
            if (group != null) {
                batches.add(List.copyOf(group));
            }
        }
        return batches;
    }

    public static List<ItemInstance> flatten(List<List<ItemInstance>> batches) {
        List<ItemInstance> out = new ArrayList<>();
        batches.forEach(out::addAll);
        return out;
    }
}
