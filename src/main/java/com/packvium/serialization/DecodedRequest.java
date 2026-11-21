package com.packvium.serialization;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.Item;
import java.util.List;
import java.util.Map;

/**
 * A wire request read into the domain, with the output units it asked for and the constraints its
 * {@code policy} compiles into.
 */
public record DecodedRequest(
        PackingConfig config,
        List<Item> items,
        List<Container> containers,
        List<FixedPlacement> fixedPlacements,
        String outputLengthUnit,
        String outputWeightUnit,
        List<Map<String, Object>> catalogVersionsUsed,
        List<PlacementConstraint> policyConstraints) {
    public DecodedRequest {
        items = List.copyOf(items);
        containers = List.copyOf(containers);
        fixedPlacements = List.copyOf(fixedPlacements);
        catalogVersionsUsed = List.copyOf(catalogVersionsUsed);
        policyConstraints = List.copyOf(policyConstraints);
    }
}
