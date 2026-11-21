package com.packvium.revisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.artifacts.OperationalArtifact;
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.RequestPacker;
import com.packvium.support.CanonicalJson;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plan revisions: an append-only, hash-chained record of exceptions. Cases and expected values are
 * Python's {@code test_revisions.py} at tag v1.4.0; byte identity with Python over the tag's
 * committed histories is the revision conformance run.
 */
// The tamper edits address trees `mutable` built, whose every object is a Map<String, Object>.
@SuppressWarnings("unchecked")
class PlanRevisionTest {
    static Map<String, Object> cubeRequest() {
        return mutable(JsonReader.parse("{\"items\":["
                + "{\"id\":\"cube\",\"quantity\":4,\"weight\":\"1000\","
                + "\"dimensions\":{\"length\":\"100\",\"width\":\"100\",\"height\":\"100\"}},"
                + "{\"id\":\"slab\",\"quantity\":1,\"dimensions\":{\"length\":\"200\",\"width\":\"100\",\"height\":\"50\"}}],"
                + "\"containers\":[{\"id\":\"box\",\"quantity\":3,"
                + "\"inner_dimensions\":{\"length\":\"200\",\"width\":\"100\",\"height\":\"200\"}},"
                + "{\"id\":\"crate\",\"inner_dimensions\":{\"length\":\"300\",\"width\":\"200\",\"height\":\"200\"}}],"
                + "\"configuration\":{\"solver_profile\":\"balanced\",\"minimum_support_ratio\":1.0}}"));
    }

    static Map<String, Object> artifactFor(Object request) {
        return OperationalArtifact.build(request, RequestPacker.pack(request));
    }

    static Map<String, Object> locked(String kind, long sequence) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("item_type", "cube");
        placement.put("container_type", "box");
        placement.put("position", new LinkedHashMap<>(Map.of("x", "0", "z", "0")));
        placement.put("orientation", "LWH");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", sequence);
        event.put("type", kind);
        event.put("placement", placement);
        return event;
    }

    static Map<String, Object> missing(String item, long quantity, long sequence) {
        return new LinkedHashMap<>(Map.of("sequence", sequence, "type", "item_missing", "item_type", item,
                "quantity", quantity));
    }

    /** The root, a lock and a missing cube, then a verification: three revisions and their artifacts. */
    static List<List<Map<String, Object>>> chain() {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        Map<String, Object> firstArtifact = artifactFor(root.get("request"));
        Map<String, Object> first = PlanRevision.derive(root, firstArtifact,
                List.of(locked("placement_locked", 1), missing("cube", 1, 2)));
        Map<String, Object> secondArtifact = artifactFor(first.get("request"));
        Map<String, Object> second = PlanRevision.derive(first, secondArtifact,
                List.of(locked("placement_verified", 3)));
        return List.of(List.of(root, first, second), Arrays.asList(null, firstArtifact, secondArtifact));
    }

    @Test
    void theRootRecordsNothingAgainstTheRequest() {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("format", "packvium-plan-revision/v1");
        expected.put("suite_version", OperationalArtifact.SUITE_VERSION);
        expected.put("revision", 0L);
        expected.put("parent", null);
        expected.put("approved", null);
        expected.put("events", List.of());
        expected.put("request", cubeRequest());
        assertEquals(expected, root);
    }

    @Test
    void aRevisionLinksItsParentAndApprovedArtifactByDigest() {
        List<List<Map<String, Object>>> chain = chain();
        Map<String, Object> first = chain.get(0).get(1);
        assertEquals(1L, first.get("revision"));
        assertEquals(PlanRevision.digest(chain.get(0).get(0)), first.get("parent"));
        Map<?, ?> approved = (Map<?, ?>) first.get("approved");
        assertEquals(PlanRevision.digest(chain.get(1).get(1)), approved.get("artifact"));
        assertEquals(((Map<?, ?>) chain.get(1).get(1).get("provenance")).get("replay"), approved.get("replay"));
    }

    @Test
    void aDigestIsSha256OverTheCanonicalBytes() throws Exception {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        byte[] bytes = PlanRevision.canonicalJson(root).getBytes(StandardCharsets.UTF_8);
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                PlanRevision.digest(root));
    }

    @Test
    void transportSpellingNeverChangesADigest() {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        Map<String, Object> reparsed = mutable(JsonReader.parse(CanonicalJson.encode(root)));
        ((Map<String, Object>) ((Map<String, Object>) reparsed.get("request")).get("configuration"))
                .put("minimum_support_ratio", 1L);
        assertEquals(PlanRevision.digest(root), PlanRevision.digest(reparsed));
    }

    @Test
    void theSameInputsDeriveTheSameBytes() {
        List<String> first = chain().get(0).stream().map(PlanRevision::canonicalJson).toList();
        List<String> second = chain().get(0).stream().map(PlanRevision::canonicalJson).toList();
        assertEquals(first, second);
    }

    @Test
    void aReturnedRevisionCannotBeChangedAndNoInputIsChanged() {
        Map<String, Object> request = cubeRequest();
        Map<String, Object> root = PlanRevision.root(request);
        assertThrows(UnsupportedOperationException.class, () -> root.put("revision", 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) ((Map<String, Object>) root.get("request")).get("items")).clear());
        PlanRevision.applyEvents(request, List.of(missing("cube", 3, 1), locked("placement_locked", 2)));
        assertEquals(cubeRequest(), request);
    }

    @Test
    void aMissingItemLowersItsQuantityAndALastOneRemovesTheType() {
        Map<String, Object> lowered = PlanRevision.applyEvents(cubeRequest(), List.of(missing("cube", 3, 1)));
        assertEquals(1L, item(lowered, 0).get("quantity"));
        Map<String, Object> removed = PlanRevision.applyEvents(cubeRequest(), List.of(missing("slab", 1, 1)));
        assertEquals(List.of("cube"), ((List<?>) removed.get("items")).stream()
                .map(entry -> ((Map<?, ?>) entry).get("id")).toList());
    }

    @Test
    void aSubstitutedContainerIsReplacedWhereItStood() {
        Map<String, Object> derived = PlanRevision.applyEvents(cubeRequest(), List.of(Map.of("sequence", 1L,
                "type", "container_substituted", "container_type", "box", "replacement",
                Map.of("id", "box-b", "inner_dimensions", Map.of("length", "210", "width", "110", "height", "210")))));
        assertEquals(List.of("box-b", "crate"), ((List<?>) derived.get("containers")).stream()
                .map(entry -> ((Map<?, ?>) entry).get("id")).toList());
    }

    @Test
    void aLockThenAVerificationOfTheSameBoxFixesItOnce() {
        Map<String, Object> derived = PlanRevision.applyEvents(cubeRequest(),
                List.of(locked("placement_locked", 1), locked("placement_verified", 2)));
        assertEquals(List.of(locked("placement_locked", 1).get("placement")), derived.get("fixed_placements"));
    }

    @Test
    void manyDistinctLocksAreAllKept() {
        List<Object> events = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Map<String, Object> event = locked("placement_locked", index + 1);
            ((Map<String, Object>) ((Map<String, Object>) event.get("placement")).get("position"))
                    .put("x", String.valueOf(index));
            events.add(event);
        }
        assertEquals(100, ((List<?>) PlanRevision.applyEvents(cubeRequest(), events).get("fixed_placements")).size());
    }

    @Test
    void aDerivedRequestSolvesWithItsFixedItemsInPlace() {
        Map<String, Object> result = RequestPacker.pack(chain().get(0).get(2).get("request"));
        List<String> fixed = new ArrayList<>();
        int placed = 0;
        for (Object container : (List<?>) result.get("containers")) {
            for (Object entry : (List<?>) ((Map<?, ?>) container).get("placements")) {
                placed++;
                if (Boolean.TRUE.equals(((Map<?, ?>) entry).get("fixed"))) {
                    fixed.add(((Map<?, ?>) container).get("id") + " " + ((Map<?, ?>) entry).get("item_id"));
                }
            }
        }
        assertEquals(List.of("box#1 cube#1"), fixed);
        assertEquals(4, placed);
    }

    @Test
    void anEventThatContradictsTheRequestIsRefused() {
        PlanRevisionException unknown = assertThrows(PlanRevisionException.class,
                () -> PlanRevision.applyEvents(cubeRequest(), List.of(missing("pallet", 1, 1))));
        assertEquals("event_conflict", unknown.errorCode());
        assertEquals("the request has no item \"pallet\"", unknown.getMessage());
        PlanRevisionException taken = assertThrows(PlanRevisionException.class, () -> PlanRevision.applyEvents(
                cubeRequest(), List.of(Map.of("sequence", 1L, "type", "container_substituted",
                        "container_type", "box", "replacement", Map.of("id", "crate")))));
        assertEquals("event 1: another container is already crate", taken.getMessage());
    }

    @Test
    void fixedItemsCannotGoMissingOrLoseTheirContainer() {
        Map<String, Object> fixed = PlanRevision.applyEvents(cubeRequest(), List.of(locked("placement_locked", 1)));
        PlanRevisionException gone = assertThrows(PlanRevisionException.class,
                () -> PlanRevision.applyEvents(fixed, List.of(missing("cube", 4, 2))));
        assertEquals(List.of("event_conflict", "event 2: 1 cube are fixed, 0 would remain"),
                List.of(gone.errorCode(), gone.getMessage()));
        PlanRevisionException moved = assertThrows(PlanRevisionException.class, () -> PlanRevision.applyEvents(
                fixed, List.of(Map.of("sequence", 2L, "type", "container_substituted", "container_type", "box",
                        "replacement", Map.of("id", "box-b")))));
        assertEquals("event 2: fixed placements are in box", moved.getMessage());
    }

    @Test
    void theLastItemTypeCannotGoMissing() {
        Map<String, Object> request = cubeRequest();
        ((List<Object>) request.get("items")).remove(1);
        PlanRevisionException error = assertThrows(PlanRevisionException.class,
                () -> PlanRevision.applyEvents(request, List.of(missing("cube", 4, 1))));
        assertEquals("event 1: no item would remain to pack", error.getMessage());
    }

    static Stream<List<Object>> malformedEvents() {
        Map<String, Object> wrongSequence = locked("placement_locked", 2);
        Map<String, Object> unknownType = locked("placement_moved", 1);
        Map<String, Object> extra = locked("placement_locked", 1);
        extra.put("note", "strapped");
        Map<String, Object> noQuantity = missing("cube", 1, 1);
        noQuantity.remove("quantity");
        Map<String, Object> booleanSequence = missing("cube", 1, 1);
        booleanSequence.put("sequence", true);
        Map<String, Object> orientation = locked("placement_locked", 1);
        ((Map<String, Object>) orientation.get("placement")).put("orientation", "XYZ");
        Map<String, Object> instance = locked("placement_locked", 1);
        ((Map<String, Object>) instance.get("placement")).put("container_instance", 0L);
        return Stream.of(List.of(), List.of(wrongSequence), List.of(unknownType), List.of(extra), List.of(noQuantity),
                List.of(missing("cube", 0, 1)), List.of(booleanSequence), List.of(orientation), List.of(instance));
    }

    @ParameterizedTest
    @MethodSource("malformedEvents")
    void aMalformedEventIsRefused(List<Object> events) {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        PlanRevisionException error = assertThrows(PlanRevisionException.class,
                () -> PlanRevision.derive(root, artifactFor(root.get("request")), events));
        assertEquals("invalid_event", error.errorCode());
    }

    @Test
    void anArtifactBuiltFromAnotherRequestIsRefused() {
        Map<String, Object> root = PlanRevision.root(cubeRequest());
        Map<String, Object> other = cubeRequest();
        item(other, 0).put("quantity", 3L);
        PlanRevisionException error = assertThrows(PlanRevisionException.class,
                () -> PlanRevision.derive(root, artifactFor(other), List.of(locked("placement_locked", 1))));
        assertEquals("invalid_artifact", error.errorCode());
    }

    @Test
    void aParentThatIsNotARevisionIsRefused() {
        PlanRevisionException error = assertThrows(PlanRevisionException.class, () -> PlanRevision.derive(
                cubeRequest(), artifactFor(cubeRequest()), List.of(locked("placement_locked", 1))));
        assertEquals(List.of("invalid_revision", "not a packvium-plan-revision/v1 document"),
                List.of(error.errorCode(), error.getMessage()));
    }

    @Test
    void anIntactChainVerifiesClean() {
        List<List<Map<String, Object>>> chain = chain();
        assertEquals(List.of(), PlanRevision.verifyChain(chain.get(0), chain.get(1)));
    }

    @Test
    void reorderedEventsAreCaught() {
        List<Map<String, Object>> revisions = mutable(chain().get(0));
        List<Object> events = (List<Object>) revisions.get(1).get("events");
        events.add(0, events.remove(1));
        assertTrue(codes(revisions, null).contains("sequence_gap"));
    }

    @Test
    void aMissingEventIsCaught() {
        List<Map<String, Object>> revisions = mutable(chain().get(0));
        ((List<Object>) revisions.get(1).get("events")).remove(1);
        assertTrue(codes(revisions, null).containsAll(Set.of("sequence_gap", "request_mismatch")));
    }

    @Test
    void anAlteredEventIsCaught() {
        List<Map<String, Object>> revisions = mutable(chain().get(0));
        ((List<Map<String, Object>>) revisions.get(1).get("events")).get(1).put("quantity", 2L);
        assertTrue(codes(revisions, null).contains("request_mismatch"));
    }

    @Test
    void anAlteredRequestBreaksTheLinkToTheNextRevision() {
        List<Map<String, Object>> revisions = mutable(chain().get(0));
        item((Map<String, Object>) revisions.get(1).get("request"), 0).put("quantity", 9L);
        assertTrue(codes(revisions, null).containsAll(Set.of("request_mismatch", "parent_mismatch")));
    }

    @Test
    void aDroppedRevisionIsCaught() {
        List<Map<String, Object>> revisions = mutable(chain().get(0));
        revisions.remove(1);
        assertTrue(codes(revisions, null).containsAll(Set.of("revision_number", "parent_mismatch", "sequence_gap")));
    }

    @Test
    void aSubstitutedArtifactIsCaught() {
        List<List<Map<String, Object>>> chain = chain();
        List<Map<String, Object>> swapped = new ArrayList<>(chain.get(1));
        swapped.set(2, chain.get(1).get(1));
        assertTrue(codes(chain.get(0), swapped).contains("artifact_mismatch"));
    }

    @Test
    void theAuditRefusesWhatIsNotAChainOrAnArtifactList() {
        assertEquals("invalid_revision", assertThrows(PlanRevisionException.class,
                () -> PlanRevision.verifyChain(Map.of("0", 1L), null)).errorCode());
        assertEquals("invalid_artifact", assertThrows(PlanRevisionException.class,
                () -> PlanRevision.verifyChain(chain().get(0), Map.of())).errorCode());
    }

    @Test
    void aValueWithNoJsonSpellingIsNamedByWhatItIs() {
        Map<String, Object> request = cubeRequest();
        request.put("metadata", Map.of("n", BigInteger.TWO.pow(60)));
        PlanRevisionException error = assertThrows(PlanRevisionException.class, () -> PlanRevision.root(request));
        assertEquals("number_out_of_range", error.errorCode());
    }

    @Test
    void aLockWithNoCanonicalFormIsRecordedForAdmissionToJudge() {
        Map<String, Object> event = locked("placement_locked", 1);
        position(event).put("x", BigInteger.TWO.pow(60));
        Map<String, Object> derived = PlanRevision.applyEvents(cubeRequest(), List.of(event));
        assertEquals(List.of(event.get("placement")), derived.get("fixed_placements"));
    }

    @Test
    void aFixedSetWithNoCanonicalFormStillDeduplicatesByFirstMatch() {
        Map<String, Object> unholdable = (Map<String, Object>) locked("placement_locked", 1).get("placement");
        ((Map<String, Object>) unholdable.get("position")).put("x", BigInteger.TWO.pow(60));
        Map<String, Object> request = cubeRequest();
        request.put("fixed_placements", List.of(locked("placement_locked", 1).get("placement"), unholdable));
        Map<String, Object> derived = PlanRevision.applyEvents(request, List.of(locked("placement_locked", 1)));
        assertEquals(List.of(locked("placement_locked", 1).get("placement"), unholdable),
                derived.get("fixed_placements"));
    }

    private static Map<String, Object> position(Map<String, Object> event) {
        return (Map<String, Object>) ((Map<String, Object>) event.get("placement")).get("position");
    }

    private static Set<String> codes(Object revisions, Object artifacts) {
        return PlanRevision.verifyChain(revisions, artifacts).stream().map(RevisionIssue::code)
                .collect(Collectors.toSet());
    }

    private static Map<String, Object> item(Map<String, Object> request, int index) {
        return ((List<Map<String, Object>>) request.get("items")).get(index);
    }

    /** A deep, mutable copy, every object a {@code Map<String, Object>}. */
    static <T> T mutable(Object tree) {
        if (tree instanceof Map<?, ?> members) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            members.forEach((name, member) -> copy.put(name, mutable(member)));
            return (T) copy;
        }
        if (tree instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>();
            elements.forEach(element -> copy.add(mutable(element)));
            return (T) copy;
        }
        return (T) tree;
    }
}
