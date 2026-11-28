package com.packvium.domain;

/**
 * An item already in a known place before the solve: loaded, or locked there.
 *
 * <p>Addressed by container type and instance rather than by a result's container index, which
 * does not exist until the solve finishes. {@code position} is the physical origin, as a result
 * reports it, so a result placement can be fixed by quoting it.
 */
public record FixedPlacement(String itemId, String containerId, Point position, Rotation rotation,
        long containerInstance) {
    // Every rule on these fields is checked where the placement sits in a request, by
    // Packer.pack, with the JSON path's refusal and pointer.

    public FixedPlacement(String itemId, String containerId, Point position, Rotation rotation) {
        this(itemId, containerId, position, rotation, 1);
    }

    public String packedContainerId() {
        return containerId + "#" + containerInstance;
    }
}
