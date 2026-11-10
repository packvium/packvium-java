package com.packvium.algorithm;

/** One item relocated from one already-packed container to another. */
public record WeightMove(String itemId, String fromContainerId, String toContainerId) {
}
