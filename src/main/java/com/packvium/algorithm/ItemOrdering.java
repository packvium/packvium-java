package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import java.util.ArrayList;
import java.util.List;

public final class ItemOrdering {
    private ItemOrdering() {
    }

    /**
     * The keys every built-in item ordering starts with.
     *
     * <p>Priority leads so a caller can bias the search. A route is unloaded last-in-first-out,
     * so later stops load first to end up underneath; items with no stop ride the whole route
     * and go to the bottom ({@code -INF}). Under maximum_value the most valuable item takes first
     * refusal on the space, behind priority and ahead of the strategy's own key (,
     *).
     */
    public static List<Object> lead(ItemInstance instance, PackingConfig config) {
        Item item = instance.item();
        double stop = item.stopIndex().map(index -> -(double) index).orElse(Double.NEGATIVE_INFINITY);
        List<Object> keys = new ArrayList<>(3);
        keys.add(-item.priority());
        keys.add(stop);
        if (config.objective().equals("maximum_value")) {
            keys.add(-item.value().orElse(0L));
        }
        return keys;
    }
}
