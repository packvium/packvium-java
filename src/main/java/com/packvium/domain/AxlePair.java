package com.packvium.domain;

import com.packvium.support.ObjectRefusal;
/** The front and rear axle of a container; front lies strictly nearer the origin. */
public record AxlePair(Axle front, Axle rear) {
    public AxlePair {
        if (front.position().ticks() >= rear.position().ticks()) {
            throw new ObjectRefusal("invalid_value",
                    "the front axle must be strictly nearer the origin than the rear axle");
        }
    }
}
