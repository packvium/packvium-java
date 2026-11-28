package com.packvium.validation;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * An item type as the request declares it, read independently of the engine's own model. Fields
 * the reference compares or prints as the request spells them stay raw JSON values.
 *
 * @param quantity the request's value, compared and printed as the reference does
 * @param allowedRotations the request's orientation codes, each a raw JSON value
 */
record ItemSpec(
        String id,
        List<BigInteger> dimensions,
        BigInteger weight,
        Object quantity,
        List<?> allowedRotations,
        boolean stackable,
        boolean mustBeOnFloor,
        BigInteger maxTopLoad,
        Ratio minimumSupportRatio,
        Set<String> tags,
        Set<String> eligibleContainerTags,
        BigInteger maxStackedItems,
        Object groundContactRule,
        BigInteger nestingHeight,
        Set<String> incompatibleTags,
        BigInteger stopIndex,
        Object value,
        Object shapeType,
        List<Vertex> hullVertices,
        BigInteger compressionRatioPpm,
        BigInteger maxCompressionPressureKpa,
        Object stackingGroup,
        Set<String> acceptsStackingGroups) {

    /** {@code orientation in allowed_rotations}. */
    boolean allows(Object orientation) {
        return allowedRotations.stream().anyMatch(allowed -> Json.equals(allowed, orientation));
    }

    boolean isConvexHull() {
        return Json.isText(shapeType, "convex_hull");
    }

    /** The item's sides in the order an orientation lays them along length, width and height. */
    List<BigInteger> rotated(String orientation) {
        int[] axes = Geometry.axes(orientation);
        return List.of(dimensions.get(axes[0]), dimensions.get(axes[1]), dimensions.get(axes[2]));
    }
}
