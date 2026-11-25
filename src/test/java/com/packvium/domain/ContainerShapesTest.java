package com.packvium.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Made-to-measure machines, flat bags and stacking groups. Every
 * expected number is the tag v1.6.0 Python's own output for the same input.
 */
class ContainerShapesTest {
    private static final long MM = 16_000;
    private static final MadeToMeasure MACHINE = new MadeToMeasure(new Sides(100 * MM, 100 * MM, 100 * MM),
            new Sides(50 * MM, 50 * MM, 50 * MM), 250, 400_000, new Sides(3 * MM, 3 * MM, 3 * MM));
    private static final Sides MAXIMUM = new Sides(500 * MM, 300 * MM, 250 * MM);

    @Test
    void theGridRoundsUpOntoTheMachineSteps() {
        assertEquals(150 * MM, MACHINE.grid(0, 1_700_000));
        assertEquals(100 * MM, MACHINE.grid(2, 100));
    }

    @Test
    void rungsAscendAndEndOnTheMachineMaximum() {
        Item block = Item.builder("block", Dimensions.ofTicks(200 * MM, 100 * MM, 50 * MM)).quantity(3).build();
        List<Sides> rungs = MACHINE.solvingEnvelopes(MAXIMUM, MadeToMeasure.instanceEdges(block.instances(), MM));
        assertEquals(List.of(new Sides(250 * MM, 200 * MM, 200 * MM), new Sides(250 * MM, 250 * MM, 250 * MM),
                new Sides(300 * MM, 300 * MM, 250 * MM), MAXIMUM), rungs);
    }

    @Test
    void boardIsPricedAndWeighedPerSquareMetreRoundedUp() {
        Sides box = new Sides(150 * MM, 100 * MM, 100 * MM);
        assertEquals(140, MACHINE.boxCostMinor(120, box));
        assertEquals(37_000, MACHINE.boxTareTicks(5_000, box));
        assertEquals(BigInteger.valueOf(8), MadeToMeasure.perSquareMetre(7, new BigInteger("256000000000001")));
    }

    @Test
    void aFabricatedBoxKeepsTheMachineAndChangesOnlyItsSizePriceAndTare() {
        Container machine = Container.builder("carton", MAXIMUM.toDimensions()).costMinor(120)
                .tareWeight(new Weight(5_000)).madeToMeasure(MACHINE).build();
        Container box = machine.fabricate(new Sides(140 * MM, 60 * MM, 99 * MM));
        assertEquals(Dimensions.ofTicks(150 * MM, 100 * MM, 100 * MM), box.innerDimensions());
        assertEquals(Optional.of(Dimensions.ofTicks(153 * MM, 103 * MM, 103 * MM)), box.outerDimensions());
        assertEquals(140, box.costMinor());
        assertEquals(37_000, box.tareWeight().ticks());
        assertEquals(machine.id(), box.id());
        assertTrue(box.madeToMeasure().isPresent());
    }

    @Test
    void aMachineWhoseGridMissesItsMaximumIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Container.builder("carton", Dimensions.ofTicks(520 * MM, 300 * MM, 250 * MM))
                        .madeToMeasure(MACHINE).build());
        assertEquals("made_to_measure increment length does not divide inner_dimensions minus minimum",
                refused.getMessage());
    }

    @Test
    void theGirthRuleAddsTheThicknessToBothFlatSides() {
        Dimensions mailer = Dimensions.ofTicks(300, 200, 50);
        assertTrue(FlatBag.fitsAlone(mailer, Dimensions.ofTicks(250, 150, 50)));
        assertFalse(FlatBag.fitsAlone(mailer, Dimensions.ofTicks(260, 150, 50)));
        assertFalse(FlatBag.fitsAlone(mailer, Dimensions.ofTicks(250, 160, 50)));
    }

    @Test
    void aBagRefusesTheFieldsAnchoredToWalls() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Container.builder("mailer", Dimensions.ofTicks(300, 200, 50)).deformableBoundary("flat_bag")
                        .accessDirections(List.of("+x")).build());
        assertEquals("access_directions cannot be combined with deformable_boundary", refused.getMessage());
    }

    @Test
    void anEmptyAcceptListIsOpenAndAFullOneIsClosed() {
        Item open = Item.builder("open", Dimensions.ofTicks(1, 1, 1)).build();
        Item drum = Item.builder("drum", Dimensions.ofTicks(1, 1, 1)).stackingGroup("drum")
                .acceptsStackingGroups(List.of("carton")).build();
        assertTrue(open.acceptsStackingGroup(Optional.empty()));
        assertTrue(drum.acceptsStackingGroup(Optional.of("carton")));
        assertFalse(drum.acceptsStackingGroup(Optional.of("drum")));
        assertFalse(drum.acceptsStackingGroup(Optional.empty()));
        assertFalse(drum.carriesItself());
        assertTrue(drum.isStackSensitive());
        assertEquals(Collections.emptyList(), open.acceptsStackingGroups());
    }
}
