package com.packvium.cli;

import com.packvium.revisions.PlanRevision;
import com.packvium.revisions.PlanRevisionException;
import com.packvium.revisions.RevisionIssue;
import com.packvium.serialization.InvalidRequestException;
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.RequestPacker;
import com.packvium.support.CanonicalJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The batch probe conformance/revisions/run.py and conformance/request_errors/run.py drive,
 * answering the protocol of the tag's {@code revisions/probe.php}: stdin is a JSON list of cases,
 * stdout one JSON line per case, in order, {@code {"error": code-or-null, "message": text-or-null,
 * "json": text-or-null}}.
 *
 * <p>{@code admit} packs a request through the engine's ordinary JSON entry point and reports a
 * request refusal, if any, with its {@code reason} and {@code field} as well (docs/ERRORS.md). A
 * refusal is an outcome, not a failure, so the probe exits 0 unless it cannot run at all; any
 * other failure of an op other than {@code admit} stops the probe, as it does PHP's.
 */
public final class RevisionProbeMain {
    private RevisionProbeMain() {
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

    private static Map<String, Object> outcome(Map<?, ?> testCase) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        boolean admit = "admit".equals(testCase.get("op"));
        try {
            String answer = answer(testCase);
            outcome.put("error", null);
            outcome.put("message", null);
            outcome.put("json", answer);
        } catch (PlanRevisionException refusal) {
            outcome.put("error", refusal.errorCode());
            outcome.put("message", refusal.getMessage());
            outcome.put("json", null);
        } catch (InvalidRequestException refusal) {
            if (!admit) {
                throw refusal;
            }
            outcome.put("error", refusal.errorCode());
            outcome.put("message", refusal.getMessage());
            outcome.put("json", null);
            outcome.put("reason", refusal.reason());
            outcome.put("field", refusal.field());
        } catch (RuntimeException failure) {
            if (!admit) {
                throw failure;
            }
            outcome.put("error", "other");
            outcome.put("message", failure.getMessage());
            outcome.put("json", null);
        }
        return outcome;
    }

    /** The canonical document, the canonical issue list, the digest, or {@code admitted}. */
    private static String answer(Map<?, ?> testCase) {
        return switch (String.valueOf(testCase.get("op"))) {
            case "root" -> PlanRevision.canonicalJson(PlanRevision.root(testCase.get("request")));
            case "derive" -> PlanRevision.canonicalJson(PlanRevision.derive(testCase.get("parent"),
                    testCase.get("artifact"), testCase.get("events")));
            case "apply" -> PlanRevision.canonicalJson(PlanRevision.applyEvents(testCase.get("request"),
                    testCase.get("events")));
            case "verify" -> PlanRevision.canonicalJson(issues(PlanRevision.verifyChain(testCase.get("revisions"),
                    testCase.get("artifacts"))));
            case "digest" -> PlanRevision.digest(testCase.get("document"));
            case "admit" -> {
                RequestPacker.pack(testCase.get("request"));
                yield "admitted";
            }
            default -> throw new IllegalArgumentException("unknown op " + testCase.get("op"));
        };
    }

    private static List<Map<String, Object>> issues(List<RevisionIssue> issues) {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (RevisionIssue issue : issues) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", issue.code());
            entry.put("revision", issue.revision());
            entry.put("detail", issue.detail());
            listed.add(entry);
        }
        return listed;
    }
}
