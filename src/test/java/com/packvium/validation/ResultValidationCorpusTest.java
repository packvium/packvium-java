package com.packvium.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.serialization.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The validator gives the reference's verdict on every pair of the shared result-validation corpus:
 * the pairs that reach every issue code and refusal kind, each with the reference validator's
 * outcome, read from their JSON text. Agreement is the cross-language rule: the same issues (code
 * and detail, in order); a contract refusal with the same message, compared up to a quoted Python
 * exception; and any refusal where the reference fails on the documents another way.
 */
class ResultValidationCorpusTest {
    private static final Pattern QUOTES_PYTHON_ERROR = Pattern.compile(
            "^(placement is missing tick coordinates: |.*: lattice_summary is missing a canonical field: )");

    @Test
    void everyPairGetsTheReferenceVerdict() throws IOException {
        List<String> disagreements = new ArrayList<>();
        List<?> cases = corpus();
        for (int index = 0; index < cases.size(); index++) {
            Map<?, ?> testCase = (Map<?, ?>) cases.get(index);
            String reason = disagreement((Map<?, ?>) testCase.get("expected"),
                    outcome((String) testCase.get("request"), (String) testCase.get("result")));
            if (reason != null) {
                disagreements.add(index + ": " + reason);
            }
        }
        assertEquals(List.of(), disagreements);
        assertTrue(cases.size() >= 100, "the corpus quietly emptied itself");
    }

    @Test
    void theCorpusReachesEveryIssueCode() throws IOException {
        Set<String> raised = new TreeSet<>();
        for (Object entry : corpus()) {
            Object issues = ((Map<?, ?>) ((Map<?, ?>) entry).get("expected")).get("issues");
            if (issues != null) {
                ((List<?>) issues).forEach(issue -> raised.add((String) ((Map<?, ?>) issue).get("code")));
            }
        }
        assertEquals(76, raised.size(), raised.toString());
    }

    private static List<?> corpus() throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                ResultValidationCorpusTest.class.getResourceAsStream("/result-validation/corpus.json"))) {
            Map<?, ?> document = (Map<?, ?>) JsonReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("packvium-result-validation-corpus/v1", document.get("format"));
            return (List<?>) document.get("cases");
        }
    }

    /** {@code {"issues": [{code, detail}...]}} or {@code {"refusal": {kind, message}}}. */
    private static Object outcome(String request, String result) {
        try {
            List<Map<String, Object>> issues = new ArrayList<>();
            for (ResultIssue issue : ResultValidator.validate(request, result)) {
                issues.add(Map.of("code", issue.code(), "detail", issue.detail()));
            }
            return issues;
        } catch (MalformedResult refusal) {
            return refusal;
        }
    }

    private static String disagreement(Map<?, ?> expected, Object actual) {
        if (expected.containsKey("issues")) {
            return expected.get("issues").equals(actual) ? null : "issues " + actual + ", reference " + expected;
        }
        Map<?, ?> reference = (Map<?, ?>) expected.get("refusal");
        if (!(actual instanceof MalformedResult refusal)) {
            return "answered " + actual + " where the reference refused " + reference;
        }
        if (!"contract".equals(reference.get("kind"))) {
            return null;
        }
        String message = (String) reference.get("message");
        Matcher quoted = QUOTES_PYTHON_ERROR.matcher(message);
        boolean same = refusal.kind() == MalformedResult.Kind.CONTRACT && (quoted.find()
                ? refusal.getMessage().startsWith(quoted.group(1)) : refusal.getMessage().equals(message));
        return same ? null : "refused " + refusal.kind() + " " + refusal.getMessage() + ", reference " + reference;
    }
}
