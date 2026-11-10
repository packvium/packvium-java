package com.packvium.nested;

import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Item;
import com.packvium.domain.PackedContainer;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.result.PackingResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs items level by level: each level's filled containers become the next level's items.
 * Stops at the first level that leaves anything unpacked, since its output is not a complete
 * set of items for the level above.
 */
public final class NestedPacker {
    public NestedPackingResult pack(List<Item> items, List<PackingLevel> levels) {
        List<Item> current = items;
        List<PackingResult> results = new ArrayList<>();
        for (PackingLevel level : levels) {
            PackingConfig config = level.config().orElseGet(PackingConfig::balanced);
            PackingResult result = new Packer(config, ExtensionRegistry.none()).pack(current, level.containers());
            results.add(result);
            if (!result.complete()) {
                break;
            }
            current = result.containers().stream().map(PackedContainer::asItem).toList();
        }
        return new NestedPackingResult(results);
    }
}
