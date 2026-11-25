package com.packvium.locks;

import static com.packvium.locks.LockFixtures.MM;
import static com.packvium.locks.LockFixtures.MM100;
import static com.packvium.locks.LockFixtures.count;
import static com.packvium.locks.LockFixtures.eightCubes;
import static com.packvium.locks.LockFixtures.placed;
import static com.packvium.locks.LockFixtures.ticks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.ConstraintResult;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.execution.Plan;
import com.packvium.extension.ExtensionRegistry;
import com.packvium.serialization.RequestPacker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Operator locks, ported from the reference's tests/test_locks.py.
 *
 * <p>The execution-plan contract names four properties, and each is a thing the layer must <em>not</em>
 * do, so the suite is written against the failure modes: a lock must not change the approved plan;
 * it must not place anything the engine would refuse; an unsatisfiable lock must come back as a
 * result, not an exception; and preservation must be measured. A fifth was found by the
 * reference: a lock must not cost the load anything it did not ask for. Two earlier designs failed
 * it by emptying a container that had been full -- one refused by item type, so eight cubes
 * became one; the other demanded the locked slot first, so a far-corner lock produced nothing --
 * and both are regression tests here.
 */
class LocksTest {
    private static final PlacementLock ORIGIN = new PlacementLock(0, "cube", "LWH", 0, 0, 0);

    // ------------------------------------------------------------- a lock binds one instance

    @Test
    void lockingOneCubeLeavesTheOtherSevenFree() {
        Map<String, Object> request = eightCubes();
        assertEquals(8, count(RequestPacker.pack(request)), "the fixture no longer tiles; the rest of this test is void");

        LockedResolve resolve = Locks.resolveWithLocks(request, List.of(ORIGIN));

        assertTrue(resolve.preserved());
        assertEquals(8, count(resolve.result()));
    }

    static Stream<List<Long>> lockedSlots() {
        return Stream.of(List.of(0L, 0L, 0L), List.of(MM100, 0L, 0L), List.of(MM100, MM100, MM100));
    }

    /**
     * The far corner is the second regression: an empty container offers only the origin, so a
     * design that filled the locked slot first could not reach (100, 100, 100) at all.
     */
    @ParameterizedTest
    @MethodSource("lockedSlots")
    void theLockedSlotIsTheOneTheOperatorNamed(List<Long> position) {
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(), List.of(new PlacementLock(0, "cube", "LWH", position)));

        assertTrue(resolve.preserved());
        assertTrue(placed(resolve.result()).contains(List.of(0, "cube", "LWH", position)));
        assertEquals(8, count(resolve.result()), "the lock cost the load items it did not ask for");
    }

    @Test
    void twoLocksOnOneTypeAreBothHonoured() {
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(),
                List.of(ORIGIN, new PlacementLock(0, "cube", "LWH", MM100, MM100, 0)));

        assertTrue(resolve.preserved(), resolve.missing()::toString);
        assertEquals(8, count(resolve.result()));
    }

    // ---------------------------------------------------------- the approved plan survives

    @Test
    void theRequestIsNotMutated() {
        Map<String, Object> request = eightCubes();
        Map<String, Object> before = eightCubes();
        Locks.resolveWithLocks(request, List.of(ORIGIN));
        assertEquals(before, request);
    }

    /** Property 1: the lock produces a candidate beside the approved plan, never over it. */
    @Test
    void theOriginalResultIsASeparateArtifact() {
        Map<String, Object> request = eightCubes();
        Map<String, Object> approved = RequestPacker.pack(request);
        String snapshot = approved.toString();

        LockedResolve resolve = Locks.resolveWithLocks(request, List.of(ORIGIN));

        assertEquals(snapshot, approved.toString());
        assertNotSame(approved, resolve.result());
    }

    // ------------------------------------- a self-contradictory set is refused before any solve

    /**
     * Whether a lock fits is a question about a request; whether a lock set contradicts itself is
     * not. Two overlapping reservations blocked every cell of a lattice that had packed eight
     * cubes, and calling that a result would be the accommodation in reverse.
     */
    @Test
    void twoLocksClaimingTheSameSpaceAreRefusedWithoutSolving() {
        LockSetError refusal = assertThrows(LockSetError.class, () -> Locks.resolveWithLocks(eightCubes(),
                List.of(ORIGIN, new PlacementLock(0, "cube", "LWH", 50 * MM, 0, 0))));
        assertEquals("two locks claim overlapping space in container 0: cube at LWH@(0, 0, 0) and cube at"
                + " LWH@(800000, 0, 0)", refusal.getMessage());
    }

    @Test
    void theSamePlacementLockedTwiceIsRefused() {
        LockSetError refusal = assertThrows(LockSetError.class,
                () -> Locks.resolveWithLocks(eightCubes(), List.of(ORIGIN, ORIGIN)));
        assertEquals("the same placement is locked twice: ('LWH', (0, 0, 0))", refusal.getMessage());
    }

    /** A lock on an item the shipment does not contain is a typo, not an infeasibility. */
    @Test
    void aLockOnAnItemTheRequestDoesNotContainIsRefused() {
        LockSetError refusal = assertThrows(LockSetError.class, () -> Locks.resolveWithLocks(eightCubes(),
                List.of(new PlacementLock(0, "wedge", "LWH", 0, 0, 0))));
        assertEquals("the request has no item type 'wedge' to lock", refusal.getMessage());
    }

    @Test
    void locksInDifferentContainersNeverContradictEachOther() {
        LockedResolve resolve = Locks.resolveWithLocks(LockFixtures.eightCubesInTwoBoxes(),
                List.of(ORIGIN, new PlacementLock(1, "cube", "LWH", 0, 0, 0)));
        assertEquals(2, resolve.locks().size());
    }

    // ------------------------------------------------ an unsatisfiable lock is an answer

    /**
     * Property 3: an operator gets diagnostics, not a stack trace. The reserved box straddles the
     * centre of an exact tiling, so it meets every cell and no cube has anywhere legal to go;
     * the ordinary search reports that in its own vocabulary.
     */
    @Test
    void aLockTheSolverCannotSatisfyReturnsAResultAndNotAnException() {
        List<Long> offGrid = List.of(50 * MM, 50 * MM, 50 * MM);
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(), List.of(new PlacementLock(0, "cube", "LWH", offGrid)));

        assertFalse(resolve.preserved());
        assertEquals(offGrid, resolve.missing().get(0).positionTicks());
        assertTrue(Set.of("best_found", "infeasible").contains(resolve.result().get("status")));
        List<?> unpacked = (List<?>) resolve.result().get("unpacked_items");
        assertFalse(unpacked.isEmpty(), "an unhonoured lock must leave a trail");
        Object level = ((Map<?, ?>) ((Map<?, ?>) unpacked.get(0)).get("proof")).get("level");
        assertTrue(Set.of("proven", "observed", "inferred", "unknown_due_to_limit").contains(level));
    }

    @Test
    void anUnhonouredLockIsNeverSoftenedIntoASuccess() {
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(),
                List.of(new PlacementLock(0, "cube", "LWH", 50 * MM, 50 * MM, 50 * MM)));
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LockedResolve(resolve.locks(), resolve.result(), true, resolve.missing()));
        assertTrue(refusal.getMessage().contains("cannot be missing a lock"));
    }

    @Test
    void anUnpreservedResolveMustSayWhichLockFailed() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new LockedResolve(List.of(), Map.of(), false, List.of()));
        assertTrue(refusal.getMessage().contains("must name the locks"));
    }

    // --------------------------------------- a lock outside the container is inert, not fatal

    /**
     * A lock is offered to every container in the solve. Without the reach test, a lock whose box
     * does not fit here would refuse every candidate and empty the container; it is reported as
     * missing instead.
     */
    @Test
    void aLockThatCannotApplyToThisContainerDoesNotEmptyIt() {
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(),
                List.of(new PlacementLock(0, "cube", "LWH", 2 * MM100, 0, 0)));

        assertFalse(resolve.preserved());
        assertEquals(8, count(resolve.result()));
        assertEquals("feasible", resolve.result().get("status"));
    }

    // ---------------------------------------------------------------- the constraint itself

    private static final Item CUBE = Item.builder("cube", Dimensions.mm(100, 100, 100)).build();
    private static final Container BOX = Container.builder("box", Dimensions.mm(200, 200, 200)).build();

    private static ConstraintContext context(long x, long y, long z) {
        return context(x, y, z, Rotation.LWH, List.of());
    }

    private static ConstraintContext context(long x, long y, long z, Rotation rotation, List<Placement> placements) {
        Dimensions dimensions = CUBE.dimensions().rotated(rotation);
        return new ConstraintContext(BOX, placements, new ItemInstance(CUBE, 1), new Point(x, y, z), rotation,
                dimensions, dimensions);
    }

    private static LockConstraint originLock() {
        return new LockConstraint(List.of(ORIGIN));
    }

    /**
     * Property 4, as the cheapest test of it: a chain is an AND, so a constraint that allows has
     * said nothing, and this one cannot be the reason a placement exists.
     */
    @Test
    void theConstraintCanOnlyEverRefuse() {
        Set<Boolean> verdicts = new HashSet<>();
        for (long x : new long[] {50 * MM, MM100}) {
            verdicts.add(originLock().evaluate(context(x, 0, 0)).allowed());
        }
        assertEquals(Set.of(true, false), verdicts);
    }

    /** What leaves the seven unlocked cubes free: a reservation is about one box. */
    @Test
    void aCandidateThatMissesTheReservedVolumeIsUntouched() {
        for (long[] position : new long[][] {{MM100, 0, 0}, {0, MM100, 0}, {MM100, MM100, MM100}}) {
            assertTrue(originLock().evaluate(context(position[0], position[1], position[2])).allowed());
        }
    }

    @Test
    void aCandidateOverlappingTheReservedVolumeIsRefused() {
        assertFalse(originLock().evaluate(context(50 * MM, 0, 0)).allowed());
    }

    @Test
    void theCandidateThatIsTheLockIsAdmitted() {
        assertTrue(originLock().evaluate(context(0, 0, 0)).allowed());
    }

    @Test
    void theRefusalNamesTheLockAndCarriesTheOperatorCode() {
        ConstraintResult verdict = originLock().evaluate(context(50 * MM, 0, 0));

        assertFalse(verdict.allowed());
        assertEquals(Locks.LOCK_VIOLATED, verdict.code());
        assertEquals("cube is locked to LWH@(0, 0, 0)", verdict.detail());
    }

    @Test
    void anUnlockedItemTypeIsUntouched() {
        LockConstraint constraint = new LockConstraint(List.of(new PlacementLock(0, "wedge", "LWH", 0, 0, 0)));
        assertTrue(constraint.evaluate(context(0, 0, 0)).allowed());
    }

    /**
     * The reservation lasts exactly as long as the lock is outstanding: once the slot is filled,
     * the ordinary overlap rule defends it, and a lock set does not accumulate cost as the
     * container fills.
     */
    @Test
    void aSatisfiedLockStopsReserving() {
        assertFalse(originLock().evaluate(context(50 * MM, 0, 0)).allowed());

        Placement occupied = new Placement(new ItemInstance(CUBE, 1), Point.ORIGIN, Rotation.LWH, CUBE.dimensions(),
                Point.ORIGIN, CUBE.dimensions(), 1.0);
        assertTrue(originLock().evaluate(context(50 * MM, 0, 0, Rotation.LWH, List.of(occupied))).allowed());
    }

    /** Same origin, different rotation: not the locked placement, and it takes the reserved volume. */
    @Test
    void theOrientationIsPartOfTheLockAndNotDecoration() {
        assertFalse(originLock().evaluate(context(0, 0, 0, Rotation.WLH, List.of())).allowed());
    }

    // ---------------------------------------------------------------- addressing a lock

    static Stream<Arguments> invalidAddresses() {
        return Stream.of(
                Arguments.of((Executable) () -> new PlacementLock(-1, "cube", "LWH", 0, 0, 0), "container index"),
                Arguments.of((Executable) () -> new PlacementLock(0, "", "LWH", 0, 0, 0), "item type"),
                Arguments.of((Executable) () -> new PlacementLock(0, "cube", "sideways", 0, 0, 0),
                        "'sideways' is not an orientation this engine emits"),
                Arguments.of((Executable) () -> new PlacementLock(0, "cube", "LWH", List.of(0L, 0L)), "three axes"),
                Arguments.of((Executable) () -> new PlacementLock(0, "cube", "LWH", nullAxis()), "exact integer ticks"),
                Arguments.of((Executable) () -> new PlacementLock(0, "cube", "LWH", 0, 0, -1), "cannot be negative"));
    }

    /** Python's {@code 1.5} and {@code True} cannot be a {@code Long}; a missing axis is what remains. */
    private static List<Long> nullAxis() {
        List<Long> ticks = new ArrayList<>(List.of(0L, 0L));
        ticks.add(null);
        return ticks;
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void anAddressTheEngineCouldNotHaveEmittedIsRefused(Executable address, String message) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, address::execute);
        assertTrue(refusal.getMessage().contains(message), refusal::getMessage);
    }

    /** The plan is where an operator sees a placement, so it is where they point at one. */
    @Test
    void aLockIsReadFromThePlansOwnReference() {
        Map<String, Object> request = eightCubes();
        Map<String, Object> result = RequestPacker.pack(request);

        List<PlacementLock> locks = Locks.locksFromPlan(Plan.build(request, result), 0, List.of("cube"));

        assertEquals(8, locks.size());
        Set<PlacementLock.Slot> expected = new HashSet<>();
        Map<?, ?> container = (Map<?, ?>) ((List<?>) result.get("containers")).get(0);
        for (Object placement : (List<?>) container.get("placements")) {
            Map<?, ?> entry = (Map<?, ?>) placement;
            expected.add(new PlacementLock.Slot((String) entry.get("orientation"), ticks(entry, "position", "x", "y", "z")));
        }
        assertEquals(expected, new HashSet<>(locks.stream().map(PlacementLock::slot).toList()));
    }

    /** Every placement locked: the load the operator approved is the load that comes back. */
    @Test
    void lockingAWholeContainerReproducesIt() {
        Map<String, Object> request = eightCubes();
        Map<String, Object> approved = RequestPacker.pack(request);
        List<PlacementLock> locks = Locks.locksFromPlan(Plan.build(request, approved), 0, List.of("cube"));

        LockedResolve resolve = Locks.resolveWithLocks(request, locks);

        assertTrue(resolve.preserved(), resolve.missing()::toString);
        assertEquals(placed(approved), placed(resolve.result()));
    }

    @Test
    void aLockReadFromAnotherContainerIndexIsNotRead() {
        Map<String, Object> request = eightCubes();
        assertEquals(List.of(), Locks.locksFromPlan(Plan.build(request, RequestPacker.pack(request)), 1, List.of("cube")));
    }

    @Test
    void onlyTheNamedItemTypesBecomeLocks() {
        Map<String, Object> request = eightCubes();
        assertEquals(List.of(), Locks.locksFromPlan(Plan.build(request, RequestPacker.pack(request)), 0, List.of("wedge")));
    }

    // ---------------------------------------------------------------- the registry

    @Test
    void anEmptyLockSetReturnsTheCallersRegistryUnchanged() {
        ExtensionRegistry base = ExtensionRegistry.none();
        assertSame(base, Locks.lockRegistry(List.of(), base));
    }

    /** An application that locks a placement must not lose its own rules by doing so. */
    @Test
    void lockingDoesNotDropTheCallersOwnConstraints() {
        PlacementConstraint mine = context -> {
            throw new AssertionError("not evaluated in this test");
        };
        ExtensionRegistry registry = Locks.lockRegistry(List.of(ORIGIN), ExtensionRegistry.withConstraints(List.of(mine)));

        assertSame(mine, registry.placementConstraints().get(0));
        assertInstanceOf(LockConstraint.class, registry.placementConstraints().get(1));
    }

    /** The instant the policy is evaluated at; reading a clock would pack differently on different days. */
    private static final long AS_OF = 1_704_067_200_000L;

    private static final String SEGREGATED = """
            {"configuration": {"solver_profile": "fast", "time_limit_ms": 300000},
             "policy": {"as_of": %d, "rules": [{"id": "hazmat-food-segregation", "version": 1, "effective_at": %d,
                         "priority": 100, "separate_tags": {"tag": "hazmat", "from_tag": "food"}}]},
             "items": [
               {"id": "drum", "quantity": 1, "tags": ["hazmat"],
                "dimensions": {"length": "100", "width": "100", "height": "100"}},
               {"id": "crate", "quantity": 1, "tags": ["food"],
                "dimensions": {"length": "100", "width": "100", "height": "100"}}],
             "containers": [{"id": "box", "quantity": 2,
                             "inner_dimensions": {"length": "200", "width": "200", "height": "200"}}]}
            """.formatted(AS_OF, AS_OF);

    /**
     * The request's policy compiles into placement constraints, and a lock arrives through the
     * same door. Replacing that set instead of adding to it would make locking anything a way to
     * switch a segregation rule off.
     */
    @Test
    void lockingDoesNotSwitchOffThePolicyTheRequestCarries() {
        Map<String, Object> request = LockFixtures.parse(SEGREGATED);
        assertEquals(2, ((List<?>) RequestPacker.pack(request).get("containers")).size(),
                "the rule no longer separates; the test is void");

        LockedResolve resolve = Locks.resolveWithLocks(request, List.of(new PlacementLock(0, "drum", "LWH", 0, 0, 0)));

        List<?> containers = (List<?>) resolve.result().get("containers");
        assertEquals(2, containers.size());
        for (Object container : containers) {
            Set<Object> types = new HashSet<>();
            for (Object placement : (List<?>) ((Map<?, ?>) container).get("placements")) {
                types.add(((Map<?, ?>) placement).get("item_type"));
            }
            assertFalse(types.equals(Set.of("drum", "crate")), "the lock let the segregation rule lapse");
        }
    }

    // ------------------------------------------------------- the dependency direction

    /**
     * A solver that imported the lock layer could grow a lock-aware path, which is exactly the
     * special case property 4 forbids. A scan of the sources is the cheapest test of it.
     */
    @Test
    void theLockLayerIsACallerOfTheEngineAndNotAPartOfIt() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "packvium");
        List<Path> engine = new ArrayList<>(List.of(root.resolve("Packer.java")));
        for (String layer : List.of("algorithm", "constraint", "validation", "serialization", "extension")) {
            try (Stream<Path> files = Files.walk(root.resolve(layer))) {
                engine.addAll(files.filter(file -> file.toString().endsWith(".java")).toList());
            }
        }
        for (Path file : engine) {
            assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("com.packvium.locks"), file::toString);
        }
    }

    /**
     * Independent validation covers a locked solve by construction -- the Packer validates every
     * solve and this is an ordinary one -- and this re-derives the cheapest of those guarantees
     * from the placements alone, so the claim does not rest only on the call graph.
     */
    @Test
    void aLockedResultIsStillAValidatedResult() {
        LockedResolve resolve = Locks.resolveWithLocks(eightCubes(), List.of(new PlacementLock(0, "cube", "LWH", MM100, 0, 0)));

        assertEquals("feasible", ((Map<?, ?>) resolve.result().get("feasibility")).get("code"));
        List<List<List<Long>>> boxes = new ArrayList<>();
        for (Object container : (List<?>) resolve.result().get("containers")) {
            for (Object placement : (List<?>) ((Map<?, ?>) container).get("placements")) {
                Map<?, ?> entry = (Map<?, ?>) placement;
                boxes.add(List.of(ticks(entry, "position", "x", "y", "z"),
                        ticks(entry, "dimensions", "length", "width", "height")));
            }
        }
        for (int index = 0; index < boxes.size(); index++) {
            for (int other = index + 1; other < boxes.size(); other++) {
                boolean overlap = true;
                for (int axis = 0; axis < 3; axis++) {
                    long origin = boxes.get(index).get(0).get(axis);
                    long size = boxes.get(index).get(1).get(axis);
                    long otherOrigin = boxes.get(other).get(0).get(axis);
                    long otherSize = boxes.get(other).get(1).get(axis);
                    overlap &= origin < otherOrigin + otherSize && otherOrigin < origin + size;
                }
                assertFalse(overlap, "a locked solve returned overlapping placements");
            }
        }
    }
}
