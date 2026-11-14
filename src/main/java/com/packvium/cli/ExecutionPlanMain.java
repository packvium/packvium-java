package com.packvium.cli;

import com.packvium.execution.Plan;
import com.packvium.serialization.JsonReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Drive the execution-plan adapter the way conformance/execution/run.py drives PHP's: a packing
 * result in, {@code Plan.canonicalJson(Plan.build([], result))} out.
 *
 * <p>With no arguments the result is read from stdin and its plan written without a newline.
 * Each argument is otherwise a result file, and each plan is written on its own line, so one
 * JVM can answer a whole corpus -- the plan's canonical form escapes every newline it holds.
 * A refusal prints its message and exits 1.
 */
public final class ExecutionPlanMain {
    private static final int REFUSED = 1;

    private ExecutionPlanMain() {
    }

    public static void main(String[] arguments) throws IOException {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream errors = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            if (arguments.length == 0) {
                out.print(plan(new String(System.in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            for (String argument : arguments) {
                out.println(plan(Files.readString(Path.of(argument), StandardCharsets.UTF_8)));
            }
            out.flush();
        } catch (RuntimeException refusal) {
            out.flush();
            errors.println(refusal.getMessage());
            System.exit(REFUSED);
        }
    }

    private static String plan(String resultJson) {
        if (!(JsonReader.parse(resultJson) instanceof Map<?, ?> result)) {
            throw new IllegalArgumentException("a packing result must be a JSON object");
        }
        return Plan.canonicalJson(Plan.build(Map.of(), asStringKeyed(result)));
    }

    /** JsonReader yields string-keyed maps; the cast only restates that. */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> asStringKeyed(Map<?, ?> result) {
        return (Map<String, ?>) result;
    }
}
