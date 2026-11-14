package com.packvium.locks;

import static com.packvium.locks.LockFixtures.MM;
import static com.packvium.locks.LockFixtures.count;
import static com.packvium.locks.LockFixtures.parse;
import static com.packvium.locks.LockFixtures.withoutClock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.serialization.RequestPacker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The six operator-lock scenarios of the reference's conformance/execution/locks.py.
 *
 * <p>A lock has no wire representation, so these cannot be fixtures piped to every engine; the
 * reference drives them against Python alone, and this drives the same scenes against Java. What
 * is certified is the behaviour 's acceptance names: a feasible lock is preserved exactly
 * and costs the load nothing; an unsafe lock is refused, never accommodated, with a deterministic
 * diagnostic; a self-contradictory lock set is refused before any solve; the approved plan is
 * unchanged; the same inputs give the same answer. Each unsafe scene is unsafe for a different
 * reason -- support, top load, route order -- and none of those reasons is known to the lock
 * layer: they are the engine's rules on the ordinary path.
 *
 * <p>The expected {@code missing} slots are the ones the reference reports at v1.2.0.
 */
class LockScenariosTest {
    /** One scenario: a request, a lock set, and either the expected preservation or a refusal. */
    private record Scenario(String name, String request, List<PlacementLock> locks, Optional<Boolean> preserved,
            List<PlacementLock.Slot> missing) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("feasible", LockFixtures.EIGHT_CUBES.formatted(""),
                    List.of(new PlacementLock(0, "cube", "LWH", 0, 0, 0)), Optional.of(true), List.of()),
            new Scenario("feasible-far-corner", LockFixtures.EIGHT_CUBES.formatted(""),
                    List.of(new PlacementLock(0, "cube", "LWH", 100 * MM, 100 * MM, 100 * MM)), Optional.of(true),
                    List.of()),
            new Scenario("colliding", LockFixtures.EIGHT_CUBES.formatted(""),
                    List.of(new PlacementLock(0, "cube", "LWH", 0, 0, 0), new PlacementLock(0, "cube", "LWH", 50 * MM, 0, 0)),
                    Optional.empty(), List.of()),
            new Scenario("unsupported", LockFixtures.UNSUPPORTED_COLUMN,
                    List.of(new PlacementLock(0, "cube", "LWH", 0, 0, 100 * MM)), Optional.of(false),
                    List.of(new PlacementLock.Slot("LWH", List.of(0L, 0L, 100 * MM)))),
            new Scenario("overload", LockFixtures.ZERO_TOP_LOAD,
                    List.of(new PlacementLock(0, "filler", "LWH", 0, 0, 100 * MM)), Optional.of(false),
                    List.of(new PlacementLock.Slot("LWH", List.of(0L, 0L, 100 * MM)))),
            new Scenario("route-incompatible", LockFixtures.ROUTE,
                    List.of(new PlacementLock(0, "first-stop", "LWH", 0, 0, 0)), Optional.of(false),
                    List.of(new PlacementLock.Slot("LWH", List.of(0L, 0L, 0L)))));

    @Test
    void thereAreSixScenarios() {
        assertEquals(6, SCENARIOS.size());
    }

    @TestFactory
    Stream<DynamicTest> everyScenarioBehavesAsTheReferenceCertifies() {
        return SCENARIOS.stream().map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> check(scenario)));
    }

    private static void check(Scenario scenario) {
        Map<String, Object> approved = RequestPacker.pack(parse(scenario.request()));
        Map<String, Object> request = parse(scenario.request());

        if (scenario.preserved().isEmpty()) {
            assertThrows(LockSetError.class, () -> Locks.resolveWithLocks(request, scenario.locks()),
                    scenario.name() + ": a self-contradictory lock set was solved anyway");
            return;
        }

        LockedResolve resolve = Locks.resolveWithLocks(request, scenario.locks());
        String name = scenario.name();
        assertEquals(scenario.preserved().get(), resolve.preserved(), name + ": preserved");
        assertEquals(scenario.missing(), slots(resolve.missing()), name + ": missing");
        assertTrue(resolve.preserved() || !resolve.missing().isEmpty(), name + ": an unhonoured lock was not named");

        // Re-derived after the locked solve rather than snapshotted before it: solving again proves
        // locking left no state behind, so the approved plan is still what the engine produces.
        assertEquals(withoutClock(approved), withoutClock(RequestPacker.pack(parse(scenario.request()))),
                name + ": the approved plan did not survive the locked solve");
        assertEquals(parse(scenario.request()), request, name + ": the request was mutated");

        // An unsafe lock is refused, never worked around: what comes back has still been through
        // the independent validator, and refusing the lock must not cost the load placements.
        Object feasibility = ((Map<?, ?>) resolve.result().get("feasibility")).get("code");
        if ("feasible".equals(feasibility) && !scenario.preserved().get()) {
            assertTrue(count(resolve.result()) >= count(approved),
                    name + ": refusing a lock cost the load " + (count(approved) - count(resolve.result()))
                            + " placement(s)");
        }

        LockedResolve again = Locks.resolveWithLocks(parse(scenario.request()), scenario.locks());
        assertEquals(withoutClock(resolve.result()), withoutClock(again.result()),
                name + ": two identical lock resolves disagreed");
        assertEquals(resolve.preserved(), again.preserved(), name + ": the diagnostic was not deterministic");
        assertEquals(resolve.missing(), again.missing(), name + ": the diagnostic was not deterministic");
    }

    private static List<PlacementLock.Slot> slots(List<PlacementLock> locks) {
        List<PlacementLock.Slot> slots = new ArrayList<>();
        for (PlacementLock lock : locks) {
            slots.add(lock.slot());
        }
        return slots;
    }
}
