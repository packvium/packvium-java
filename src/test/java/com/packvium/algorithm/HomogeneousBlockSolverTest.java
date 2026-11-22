package com.packvium.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.domain.ShapeType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The homogeneous-block solver reports the support it really gives, and leaves to the per-item
 * search what a rigid block cannot honour. Held to PHP's
 * {@code SolverTest} at tag v1.5.0.
 */
class HomogeneousBlockSolverTest {
    private static final Container CRATE = Container.builder("crate", Dimensions.mm(100, 100, 100)).quantity(1L)
            .build();

    private static List<ItemInstance> instances(Item item) {
        List<ItemInstance> out = new ArrayList<>();
        for (long sequence = 1; sequence <= item.quantity(); sequence++) {
            out.add(new ItemInstance(item, sequence));
        }
        return out;
    }

    /** One 60x60x40 base and three 40x60x50 tops in a 100 mm crate: the base ends on two tops and overhangs them. */
    private static List<ItemInstance> aBaseAndThreeTops() {
        List<ItemInstance> items = new ArrayList<>(instances(Item.builder("base", Dimensions.mm(60, 60, 40)).build()));
        items.addAll(instances(Item.builder("top", Dimensions.mm(40, 60, 50)).quantity(3).build()));
        return items;
    }

    /** The share of a placement's base on the floor or on a top face, from geometry alone. */
    private static double restingShare(List<Placement> placements, Placement placement) {
        AxisAlignedBox box = placement.envelopeBox();
        if (box.origin().z() == 0) {
            return 1.0;
        }
        long resting = 0;
        for (Placement other : placements) {
            AxisAlignedBox below = other.envelopeBox();
            if (below.z2() == box.origin().z()) {
                resting += below.overlapAreaXY(box);
            }
        }
        return (double) resting / placement.envelopeDimensions().baseAreaTicks();
    }

    private static SingleContainerSolution solve(List<ItemInstance> items, PackingConfig config) {
        return new HomogeneousBlockSolver(List.of()).packOne(CRATE, 1, items, config, new SearchStats(),
                Deadline.ofMilliseconds(60_000));
    }

    @Test
    void aBlockSetOnASmallerOneReportsTheSupportItReallyHas() {
        List<Placement> placements = solve(aBaseAndThreeTops(), PackingConfig.quality()).state().placements;
        Placement base = placements.stream().filter(p -> p.instance().item().id().equals("base")).findFirst()
                .orElseThrow();
        assertTrue(base.envelopeOrigin().z() > 0);
        assertTrue(Math.abs(base.supportRatio() - 5.0 / 6.0) < 1e-9, "support " + base.supportRatio());
        assertEquals(restingShare(placements, base), base.supportRatio());
    }

    @Test
    void theBlockSolverLeavesARequestThatAsksForSupportToThePerItemSearch() {
        PackingConfig config = PackingConfig.builder(SolverProfile.QUALITY).minimumSupportRatio(1.0)
                .multiStartOrders(24).maxCandidatesPerItem(16).containerPlanBeamWidth(16)
                .containerPlanNodeLimit(100_000).build();
        List<Placement> placements = solve(aBaseAndThreeTops(), config).state().placements;
        assertFalse(placements.isEmpty());
        for (Placement placement : placements) {
            assertEquals(1.0, restingShare(placements, placement), placement.instance().id());
        }
    }

    @Test
    void blockSearchFallsBackForCompressibleItems() {
        Container box = Container.builder("box", Dimensions.mm(100, 100, 200)).quantity(1L).build();
        Item sponge = Item.builder("sponge", Dimensions.mm(100, 100, 100)).quantity(2)
                .shapeType(ShapeType.COMPRESSIBLE).compressionRatioPpm(200_000L).maxCompressionPressureKpa(50L)
                .build();
        SingleContainerSolution solution = new HomogeneousBlockSolver(List.of()).packOne(box, 1, instances(sponge),
                PackingConfig.quality(), new SearchStats(), Deadline.ofMilliseconds(60_000));
        assertEquals(2, solution.state().placements.size());
        assertTrue(solution.unpacked().isEmpty());
    }
}
