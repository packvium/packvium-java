package com.packvium.nested;

import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import java.util.List;
import java.util.Optional;

/** One level of a nested pack: the containers its contents go into, and how to pack them. */
public record PackingLevel(String name, List<Container> containers, Optional<PackingConfig> config) {
    public PackingLevel {
        containers = List.copyOf(containers);
    }

    public PackingLevel(String name, List<Container> containers) {
        this(name, containers, Optional.empty());
    }
}
