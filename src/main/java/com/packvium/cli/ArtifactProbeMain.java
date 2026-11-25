package com.packvium.cli;

import com.packvium.artifacts.ArtifactExports;
import com.packvium.artifacts.OperationalArtifact;
import com.packvium.artifacts.OperationalArtifactException;
import com.packvium.serialization.JsonReader;
import com.packvium.support.CanonicalJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test harness for cross-engine conformance; not a supported interface.
 *
 * <p>It answers operational-artifact cases in batch: stdin is a JSON list of cases, stdout one
 * JSON line per case, in order, {@code {"csv": ..., "error": code-or-null, "html": ...,
 * "json": ...}}. A case of kind {@code export} names a stored artifact ({@code artifact_path}); any other
 * case names a request, a result and loading orders keyed by container index as a string. A
 * refusal is an outcome, not a failure, so the harness exits 0 unless it cannot run at all. Each
 * line is written in canonical form, which escapes every newline the exports hold.
 */
public final class ArtifactProbeMain {
    private ArtifactProbeMain() {
    }

    public static void main(String[] arguments) throws IOException {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        Object cases = JsonReader.parse(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        for (Object entry : (List<?>) cases) {
            out.print(CanonicalJson.encode(outcome((Map<?, ?>) entry)));
            out.print('\n');
        }
        out.flush();
    }

    private static Map<String, Object> outcome(Map<?, ?> testCase) throws IOException {
        Map<String, Object> outcome = new LinkedHashMap<>();
        try {
            Object artifact = "export".equals(testCase.get("kind"))
                    ? OperationalArtifact.parse(read(testCase.get("artifact_path")))
                    : OperationalArtifact.fromJson(read(testCase.get("request_path")), read(testCase.get("result_path")),
                            loadingOrders((Map<?, ?>) testCase.get("loading_orders")));
            String json = ArtifactExports.json(artifact);
            String csv = ArtifactExports.csv(artifact);
            String html = ArtifactExports.workOrderHtml(artifact);
            outcome.put("error", null);
            outcome.put("json", json);
            outcome.put("csv", csv);
            outcome.put("html", html);
        } catch (OperationalArtifactException refusal) {
            outcome.put("error", refusal.errorCode());
            outcome.put("json", null);
            outcome.put("csv", null);
            outcome.put("html", null);
        }
        return outcome;
    }

    private static Map<Integer, List<Integer>> loadingOrders(Map<?, ?> orders) {
        Map<Integer, List<Integer>> byIndex = new LinkedHashMap<>();
        orders.forEach((index, order) -> {
            List<Integer> steps = new ArrayList<>();
            for (Object step : (List<?>) order) {
                steps.add(Math.toIntExact((Long) step));
            }
            byIndex.put(Integer.parseInt((String) index), steps);
        });
        return byIndex;
    }

    private static String read(Object path) throws IOException {
        return Files.readString(Path.of((String) path), StandardCharsets.UTF_8);
    }
}
