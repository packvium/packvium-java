package com.packvium.domain;

/**
 * An item already in a known place before the solve: loaded, or locked there.
 *
 * <p>Addressed by container type and instance rather than by a result's container index, which
 * does not exist until the solve finishes. {@code position} is the physical origin, as a result
 * reports it, so a result placement can be fixed by quoting it (docs/PLAN-REVISIONS.md).
 */
public record FixedPlacement(String itemId, String containerId, Point position, Rotation rotation,
        long containerInstance) {

    public FixedPlacement {
        if (containerInstance < 1) {
            throw new IllegalArgumentException("container_instance counts from 1");
        }
    }

    public FixedPlacement(String itemId, String containerId, Point position, Rotation rotation) {
        this(itemId, containerId, position, rotation, 1);
    }

    public String packedContainerId() {
        return containerId + "#" + containerInstance;
    }
}
