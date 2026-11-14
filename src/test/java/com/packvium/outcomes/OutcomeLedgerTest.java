package com.packvium.outcomes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The append-only outcome ledger, ported from the reference's tests/test_outcomes.py.
 *
 * <p>The distinction worth reading twice is {@code viewAsOf} against {@code currentView}: both
 * fold the same immutable events, and only one of them is safe to reconstruct a past belief with.
 */
class OutcomeLedgerTest {
    static OutcomeEvent shipped(String eventId, String decisionId, long at, String carton, Optional<String> supersedes) {
        return new OutcomeEvent(eventId, decisionId, OutcomeEventType.ACTUAL_CARTON,
                Map.of("carton_id", carton, "catalog_version", 1L), at, supersedes);
    }

    static OutcomeEvent shipped(String eventId, String decisionId, long at) {
        return shipped(eventId, decisionId, at, "box-a", Optional.empty());
    }

    static List<String> ids(List<OutcomeEvent> events) {
        return events.stream().map(OutcomeEvent::eventId).toList();
    }

    @Nested
    class RecordingIsAppendOnlyAndIdempotent {
        @Test
        void aRecordedEventComesBackForItsDecision() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100));
            assertEquals(List.of("e1"), ids(ledger.eventsForDecision("d1")));
        }

        @Test
        void anUnknownDecisionHasAnEmptyHistoryRatherThanRaising() {
            assertEquals(List.of(), new OutcomeLedger().eventsForDecision("never-shipped"));
        }

        /** Duplicate delivery is a fact of every queue: it must neither double the event nor fail. */
        @Test
        void recordingTheIdenticalEventTwiceIsANoOp() {
            OutcomeLedger ledger = new OutcomeLedger();
            OutcomeEvent first = ledger.record(shipped("e1", "d1", 100));
            OutcomeEvent again = ledger.record(shipped("e1", "d1", 100));
            assertSame(first, again);
            assertEquals(1, ledger.eventsForDecision("d1").size());
        }

        /** A duplicate delivery repeats an event; it does not carry a different one under the same id. */
        @Test
        void reusingAnEventIdForDifferentContentIsRefused() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100, "box-a", Optional.empty()));
            DuplicateEventMismatchError refusal = assertThrows(DuplicateEventMismatchError.class,
                    () -> ledger.record(shipped("e1", "d1", 100, "box-b", Optional.empty())));
            assertEquals("event id 'e1' was already recorded with different content", refusal.getMessage());
        }

        @Test
        void eventsComeBackInRecordedOrder() {
            OutcomeLedger ledger = new OutcomeLedger();
            long[] times = {300, 100, 200};
            for (int index = 0; index < times.length; index++) {
                ledger.record(shipped("e" + (index + 1), "d1", times[index]));
            }
            assertEquals(List.of("e1", "e2", "e3"), ids(ledger.eventsForDecision("d1")));
        }

        @Test
        void decisionsAreKeptApart() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100));
            ledger.record(shipped("e2", "d2", 100));
            assertEquals(List.of("e1"), ids(ledger.eventsForDecision("d1")));
            assertEquals(List.of("e2"), ids(ledger.eventsForDecision("d2")));
        }
    }

    @Nested
    class ACorrectionSupersedesRatherThanOverwrites {
        private OutcomeLedger corrected() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100, "box-a", Optional.empty()));
            ledger.record(shipped("e2", "d1", 200, "box-b", Optional.of("e1")));
            return ledger;
        }

        @Test
        void theCorrectionReplacesTheOriginalInTheCurrentView() {
            assertEquals(List.of("e2"), ids(corrected().currentView("d1")));
        }

        /** A correction that erased what it corrected would make the ledger a mutable store. */
        @Test
        void theOriginalIsStillInTheUnfoldedHistory() {
            assertEquals(List.of("e1", "e2"), ids(corrected().eventsForDecision("d1")));
        }

        @Test
        void supersedingAnEventTheLedgerHasNeverSeenIsRefused() {
            OutcomeEventNotFoundError refusal = assertThrows(OutcomeEventNotFoundError.class,
                    () -> new OutcomeLedger().record(shipped("e2", "d1", 200, "box-a", Optional.of("ghost"))));
            assertEquals("event 'e2' supersedes unknown event 'ghost'", refusal.getMessage());
        }

        @Test
        void anEventCannotSupersedeItself() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> shipped("e1", "d1", 100, "box-a", Optional.of("e1")));
            assertTrue(refusal.getMessage().contains("cannot supersede itself"));
        }
    }

    /**
     * {@code viewAsOf} exists because {@code currentView} folds every correction ever recorded:
     * a holdout score built on it reports a backtest nobody could have run.
     */
    @Nested
    class TimeTravelIsNarrowerThanTheCurrentView {
        private OutcomeLedger ledger() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100, "box-a", Optional.empty()));
            ledger.record(shipped("e2", "d1", 300, "box-b", Optional.of("e1")));
            return ledger;
        }

        @Test
        void beforeAnythingWasRecordedTheViewIsEmpty() {
            assertEquals(List.of(), ledger().viewAsOf("d1", 50));
        }

        @Test
        void betweenTheEventAndItsCorrectionTheOriginalStillStands() {
            List<OutcomeEvent> view = ledger().viewAsOf("d1", 200);
            assertEquals(List.of("e1"), ids(view));
            assertEquals("box-a", view.get(0).payload().get("carton_id"));
        }

        @Test
        void afterTheCorrectionTheCorrectionApplies() {
            assertEquals(List.of("e2"), ids(ledger().viewAsOf("d1", 400)));
        }

        /** An event recorded at the instant the question is asked is not yet part of what was believed. */
        @Test
        void theBoundaryIsStrictlyBefore() {
            assertEquals(List.of(), ledger().viewAsOf("d1", 100));
        }

        @Test
        void theCurrentViewDisagreesWithThePastOneOnPurpose() {
            OutcomeLedger ledger = ledger();
            assertEquals(List.of("e1"), ids(ledger.viewAsOf("d1", 200)));
            assertEquals(List.of("e2"), ids(ledger.currentView("d1")));
        }

        @Test
        void aNegativeInstantIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> ledger().viewAsOf("d1", -1));
            assertEquals("as-of time cannot be negative", refusal.getMessage());
        }
    }

    @Nested
    class SeveralEventKindsCoexistOnOneDecision {
        @Test
        void aShipmentAndItsDamageBothStand() {
            OutcomeLedger ledger = new OutcomeLedger();
            ledger.record(shipped("e1", "d1", 100));
            ledger.record(new OutcomeEvent("e2", "d1", OutcomeEventType.DAMAGE,
                    Map.of("reason_code", "crushed_corner", "severity", "minor"), 200));
            List<String> kinds = ledger.currentView("d1").stream().map(event -> event.eventType().value()).sorted().toList();
            assertEquals(List.of("actual_carton", "damage"), kinds);
        }
    }

    @Nested
    class AnEventMustNameItsDecision {
        /** The decision id is the only index the ledger has; an event without one could never be read back. */
        @Test
        void anEventWithoutADecisionIdIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> shipped("e1", "", 100));
            assertEquals("decision_id is required", refusal.getMessage());
        }
    }

    /** The PII guard the reference's module docstring describes, which its own suite reaches only indirectly. */
    @Nested
    class APayloadIsAClosedVocabulary {
        @Test
        void aFieldOutsideTheEventTypesVocabularyIsRefused() {
            UnsupportedOutcomeFieldError refusal = assertThrows(UnsupportedOutcomeFieldError.class,
                    () -> new OutcomeEvent("e1", "d1", OutcomeEventType.DAMAGE,
                            Map.of("reason_code", "crushed_corner", "note", "Mrs Smith, 4 High St"), 100));
            assertEquals("event type 'damage' does not support field(s) ['note']; allowed: ['reason_code', 'severity']",
                    refusal.getMessage());
        }
    }
}
