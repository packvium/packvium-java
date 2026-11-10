package com.packvium.domain;

/** One distinct orientation of an item's box and the rotation that produces it. */
public record OrientedDimensions(Rotation rotation, Dimensions dimensions) {
}
