package com.packvium.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every shipped example the way its Javadoc tells a reader to, and checks what it prints
 * twice: against the committed Java output, and against what the PHP example of the same name
 * prints at tag v1.0.0.
 *
 * <p>An example that still runs but now prints a different answer teaches a different thing, and
 * only a committed output notices that. The PHP comparison keeps the answers themselves -- score
 * vectors, placements, refusal codes -- identical across languages. Lines that only name the
 * language (a file name, a command, how a missing value is spelt) are the one permitted
 * difference, and each is listed in {@link #LANGUAGE_WORDING}. Nothing is masked: the examples
 * print no elapsed times, versions or backend names, which are the only text the tag's own runner
 * masks.
 */
class ExamplesTest {
    private static final Path EXAMPLES = Path.of("examples");
    private static final Path EXPECTED = Path.of("src", "test", "resources", "examples");
    private static final Path CLASSES = Path.of("target", "classes");
    private static final long TIMEOUT_SECONDS = 300;
    private static final Pattern CLASS_JAVADOC = Pattern.compile("/\\*\\*(.*?)\\*/\\s*public\\s+final\\s+class",
            Pattern.DOTALL);

    /** Per example, each Java line that differs from PHP only in naming the language, and its PHP line. */
    private static final Map<String, Map<String, String>> LANGUAGE_WORDING = Map.of(
            "basic", Map.of(
                    "the full result as a plain array is what Serialization.java explores:",
                    "the full result as a plain array is what serialization.php explores:"),
            "serialization", Map.of(
                    // The refusal names its engine, and "Java" is one character longer than "PHP",
                    // so the example's 110-character excerpt ends one character earlier.
                    "  what one looks like, from an engine that is not: unsupported_feature: the Java engine does not"
                            + " yet implement item.shape_type=convex_hull; the request was rejec",
                    "  what one looks like, from an engine that is not: unsupported_feature: the PHP engine does not"
                            + " yet implement item.shape_type=convex_hull; the request was reject",
                    "the CLI takes exactly the document above:  echo '...' | java -jar target/packvium-1.0.0.jar",
                    "the CLI takes exactly the document above:  echo '...' | php bin/packvium"),
            "commerce", Map.of(
                    "   nothing matched, so the shipment is allowed with no citation: null",
                    "   nothing matched, so the shipment is allowed with no citation: NULL"));

    @TestFactory
    Stream<DynamicTest> everyExamplePrintsItsRecordedAnswer() throws IOException {
        List<Path> examples;
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            examples = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(examples.isEmpty(), "no examples found in " + EXAMPLES.toAbsolutePath());
        return examples.stream().map(example -> DynamicTest.dynamicTest(example.getFileName().toString(),
                () -> verify(example)));
    }

    private static void verify(Path example) throws IOException, InterruptedException {
        String name = stem(example);
        assertTrue(runItLine(Files.readString(example, StandardCharsets.UTF_8)),
                example + " has no class Javadoc with a \"Run it:\" line");

        Process process = new ProcessBuilder(javaBinary(), "-cp", CLASSES.toString(), example.toString()).start();
        // Drained concurrently: a full stderr pipe would block the example before it exits.
        CompletableFuture<String> errors = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        String output = read(process.getInputStream());
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), example + " did not finish");
        assertEquals(0, process.exitValue(), example + " failed:\n" + errors.join());

        assertEquals(expected(name + ".out"), output, example + " drifted from its recorded output");
        assertEquals(expected("php/" + name + ".out"), inPhpWording(name, output),
                example + " answers differently from the PHP example");
    }

    private static String inPhpWording(String name, String output) {
        Map<String, String> wording = LANGUAGE_WORDING.getOrDefault(name, Map.of());
        String[] lines = output.split("\n", -1);
        long replaced = 0;
        for (int index = 0; index < lines.length; index++) {
            String php = wording.get(lines[index]);
            if (php != null) {
                lines[index] = php;
                replaced++;
            }
        }
        // A listed line that no longer appears is stale wording, not a pass.
        assertEquals(wording.size(), replaced, name + " no longer prints every line in LANGUAGE_WORDING");
        return String.join("\n", lines);
    }

    private static String expected(String file) throws IOException {
        return Files.readString(EXPECTED.resolve(file), StandardCharsets.UTF_8);
    }

    private static boolean runItLine(String source) {
        Matcher javadoc = CLASS_JAVADOC.matcher(source);
        return javadoc.find() && javadoc.group(1).contains("Run it:");
    }

    private static String stem(Path example) {
        String file = example.getFileName().toString();
        return file.substring(0, file.length() - ".java".length()).toLowerCase(Locale.ROOT);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
