package com.packvium.extension;

import com.packvium.domain.ItemInstance;
import java.util.List;

public interface ItemOrderStrategy {
    String name();

    List<ItemInstance> order(List<ItemInstance> items, long seed);
}
