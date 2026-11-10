package com.packvium.serialization;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Item;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A wire request read into the domain, with the output units it asked for. */
public record DecodedRequest(
        PackingConfig config,
        List<Item> items,
        List<Container> containers,
        String outputLengthUnit,
        String outputWeightUnit,
        List<Map<String, Object>> catalogVersionsUsed,
        Optional<Object> policy) {
    public DecodedRequest {
        items = List.copyOf(items);
        containers = List.copyOf(containers);
        catalogVersionsUsed = List.copyOf(catalogVersionsUsed);
    }
}
