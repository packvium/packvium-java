package com.packvium.cli;

import com.packvium.commerce.Commerce;
import com.packvium.commerce.CommerceInputException;
import com.packvium.serialization.JsonReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * Drive the exported commerce surface from stdin to stdout -- the Java half of the contract
 * conformance/commerce/adapters/php_cli.php implements: one case object in, one canonical
 * result document out. It calls the public {@link Commerce} methods, so what the harness
 * compares is the exported API.
 *
 * <p>Exit codes follow the PHP adapter: 1 with "input error: ..." for a malformed request,
 * 2 for an unknown operation, and 255 -- PHP's fatal-error status -- for anything else.
 */
public final class CommerceMain {
    private static final int INPUT_ERROR = 1;
    private static final int UNKNOWN_OPERATION = 2;
    private static final int FATAL = 255;

    private static final Map<String, BinaryOperator<Map<String, Object>>> OPERATIONS = operations();

    private CommerceMain() {
    }

    public static void main(String[] arguments) throws IOException {
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        PrintStream errors = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            Map<String, Object> testCase = asArray(JsonReader.parse(input));
            Object operation = testCase.get("operation");
            BinaryOperator<Map<String, Object>> call = operation instanceof String name ? OPERATIONS.get(name) : null;
            if (call == null) {
                errors.println("unknown operation '" + (operation instanceof String name ? name : "") + "'");
                System.exit(UNKNOWN_OPERATION);
            }
            Map<String, Object> result = call.apply(asArray(testCase.get("document")), asArray(testCase.get("request")));
            PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
            out.print(Commerce.canonicalJson(result));
            out.flush();
        } catch (CommerceInputException error) {
            errors.println("input error: " + error.getMessage());
            System.exit(INPUT_ERROR);
        } catch (RuntimeException error) {
            errors.println("fatal error: " + error.getClass().getName() + ": " + error.getMessage());
            System.exit(FATAL);
        }
    }

    private static Map<String, BinaryOperator<Map<String, Object>>> operations() {
        Map<String, BinaryOperator<Map<String, Object>>> operations = new LinkedHashMap<>();
        operations.put("quote", Commerce::quote);
        operations.put("evaluate_policy", Commerce::evaluatePolicy);
        operations.put("catalog_version_info", Commerce::catalogVersionInfo);
        return operations;
    }

    /**
     * PHP's array parameter: a JSON object, or a JSON list keyed by its indices. Anything
     * else is a TypeError there, so it is a fatal error here, never an input error.
     */
    private static Map<String, Object> asArray(Object value) {
        Map<String, Object> members = new LinkedHashMap<>();
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                members.put(Integer.toString(index), list.get(index));
            }
            return members;
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalStateException("expected an array, got " + (value == null ? "null" : "a scalar"));
        }
        for (Map.Entry<?, ?> member : object.entrySet()) {
            members.put(member.getKey().toString(), member.getValue());
        }
        return members;
    }
}
