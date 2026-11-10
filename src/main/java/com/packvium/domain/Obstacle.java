package com.packvium.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An excluded zone. {@code additionalBoxes} expresses a non-rectangular shape (a wheel arch,
 * a tapered roof) as a union of exact boxes rather than modelling slants directly, so the
 * geometry stays integral.
 */
public record Obstacle(String id, AxisAlignedBox box, List<AxisAlignedBox> additionalBoxes) {
    public Obstacle {
        additionalBoxes = List.copyOf(additionalBoxes);
    }

    public Obstacle(String id, AxisAlignedBox box) {
        this(id, box, List.of());
    }

    public List<AxisAlignedBox> boxes() {
        List<AxisAlignedBox> all = new ArrayList<>(additionalBoxes.size() + 1);
        all.add(box);
        all.addAll(additionalBoxes);
        return List.copyOf(all);
    }
}
