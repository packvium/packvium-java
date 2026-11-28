package com.packvium.validation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A container type as the request declares it, read independently of the engine's own model.
 *
 * @param id the request's id value, printed as the reference prints it
 * @param tagLimits each limit by tag, sorted by tag, the order the reference walks them in
 * @param axles each axle's position and load limit (null when it has none), or null
 * @param rateTable the request's rate card, kept raw: the landed cost is re-derived from it
 * @param accessDirections the walls this container may be unloaded through, in canonical order
 */
record ContainerSpec(
        Object id,
        List<BigInteger> inner,
        List<BigInteger> outer,
        BigInteger costMinor,
        BigInteger maxPayload,
        BigInteger maxItems,
        BigInteger quantity,
        List<Cuboid> obstacles,
        Set<String> tags,
        List<Map.Entry<String, BigInteger>> tagLimits,
        Ratio voidFillReserveRatio,
        BigInteger tareWeight,
        List<BigInteger[]> axles,
        BigInteger maxStackDensity,
        Object rateTable,
        List<String> accessDirections,
        boolean flatBag,
        boolean reportsAchievedExtent,
        MachineSpec madeToMeasure) {

    BigInteger volume() {
        return inner.get(0).multiply(inner.get(1)).multiply(inner.get(2));
    }

    /** The box a made-to-measure container was recomputed as: its size, outer size, price and tare. */
    ContainerSpec fabricated(List<BigInteger> box, List<BigInteger> outerBox, BigInteger cost, BigInteger tare) {
        return new ContainerSpec(id, box, outerBox, cost, maxPayload, maxItems, quantity, obstacles, tags, tagLimits,
                voidFillReserveRatio, tare, axles, maxStackDensity, rateTable, accessDirections, flatBag,
                reportsAchievedExtent, madeToMeasure);
    }
}
