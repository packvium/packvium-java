package com.packvium.objective;

import com.packvium.config.PackingConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Named objectives a caller may select through {@code PackingConfig.objective}. Each factory
 * takes the active config, so a parametrised objective pulls its own inputs from it without
 * widening {@link SolutionScorer}.
 */
public final class ObjectiveRegistry {
    private static final Map<String, Function<PackingConfig, SolutionScorer>> FACTORIES = factories();

    private ObjectiveRegistry() {
    }

    public static SolutionScorer resolve(String name, PackingConfig config) {
        Function<PackingConfig, SolutionScorer> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new UnknownObjectiveException(
                    "unknown objective " + name + "; expected one of " + String.join(", ", FACTORIES.keySet()));
        }
        return factory.apply(config);
    }

    private static Map<String, Function<PackingConfig, SolutionScorer>> factories() {
        Map<String, Function<PackingConfig, SolutionScorer>> named = new LinkedHashMap<>();
        named.put("default", config -> new DefaultSolutionScorer());
        named.put("lowest_cost", config -> new LowestCostSolutionScorer());
        named.put("shipping_cost", ShippingCostSolutionScorer::fromConfig);
        // Landed cost prices billed weight, so it needs the same dimensional-weight inputs.
        named.put("lowest_landed_cost", LandedCostSolutionScorer::fromConfig);
        named.put("open_dimension_height", config -> new OpenDimensionSolutionScorer());
        named.put("maximum_value", config -> new MaximumValueSolutionScorer());
        return Collections.unmodifiableMap(named);
    }
}
