package com.packvium.algorithm;

import com.packvium.domain.ItemInstance;
import com.packvium.domain.PackedContainer;
import java.util.List;

/**
 * What the solve starts from: the fixed containers, and the items still to place.
 *
 * <p>{@code containers} are in opening order -- request container order, then instance -- each
 * holding only its fixed placements. {@code free} is every instance a fixed placement did not
 * take, in request order.
 */
public record FixedLoad(List<PackedContainer> containers, List<ItemInstance> free) {
    public FixedLoad {
        containers = List.copyOf(containers);
        free = List.copyOf(free);
    }
}
