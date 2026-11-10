package com.packvium.domain;

import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.util.Optional;

/**
 * One axle's position along the container's length axis and its own limit.
 *
 * <p>Two-axle only (front, rear): the load on more than two supports is statically
 * indeterminate without further assumptions this library has no basis for making.
 */
public record Axle(Length position, Optional<Weight> maxLoad) {
}
