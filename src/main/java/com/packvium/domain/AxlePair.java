package com.packvium.domain;

/** The front and rear axle of a container; front lies strictly nearer the origin. */
public record AxlePair(Axle front, Axle rear) {
    public AxlePair {
        if (front.position().ticks() >= rear.position().ticks()) {
            throw new IllegalArgumentException(
                    "The front axle must be strictly nearer the origin than the rear axle");
        }
    }
}
