package com.packvium.algorithm;

import com.packvium.domain.Dimensions;
import com.packvium.domain.Point;

/** An empty maximal box inside a container. */
public record Space(Point origin, Dimensions dimensions) {
}
