package com.packvium.cli;

import com.packvium.serialization.JsonReader;
import com.packvium.support.CanonicalJson;
import com.packvium.validation.MalformedResult;
import com.packvium.validation.ResultIssue;
import com.packvium.validation.ResultValidator;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test harness for cross-engine conformance of the result validator; not a supported interface.
 *
 * <p>stdin is a JSON list of {@code {"request": text, "result": text}} cases, each document as JSON
 * text; stdout is one JSON line per case, in order: {@code {"issues": [{"code", "detail"}...]}}, or
 * {@code {"refusal": {"kind": "contract" | "shape", "message"}}} when the validator refuses the
 * documents. A refusal is an outcome, so the harness exits 0 unless it cannot run at all.
 */
public final class ValidateMain {
    private ValidateMain() {
    }

    public static void main(String[] arguments) throws IOException {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        Object cases = JsonReader.parse(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        for (Object entry : (List<?>) cases) {
            Map<?, ?> testCase = (Map<?, ?>) entry;
            out.print(CanonicalJson.encode(outcome(testCase.get("request"), testCase.get("result"))));
            out.print('\n');
        }
        out.flush();
    }

    /** The validator's answer for two documents, each JSON text or an already decoded document. */
    static Map<String, Object> outcome(Object request, Object result) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        try {
            List<ResultIssue> issues = request instanceof String requestText && result instanceof String resultText
                    ? ResultValidator.validate(requestText, resultText)
                    : ResultValidator.validate(request, result);
            List<Map<String, Object>> listed = new ArrayList<>();
            for (ResultIssue issue : issues) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("code", issue.code());
                pair.put("detail", issue.detail());
                listed.add(pair);
            }
            outcome.put("issues", listed);
        } catch (MalformedResult refusal) {
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("kind", refusal.kind().wireName());
            described.put("message", refusal.getMessage());
            outcome.put("refusal", described);
        }
        return outcome;
    }
}
