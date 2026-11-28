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
 * twice: against the committed Java output, and against what the PHP or Python example of the
 * same name prints in Packvium 1.7.0.
 *
 * <p>The comparison is with PHP, the port's model, except where PHP has no such example:
 * {@code execution} continues into operator locks, {@code intelligence} and {@code rebalancing}
 * exist only in Python, and PHP's {@code artifacts} packs another request without a loading order,
 * so those four are compared with the Python output ({@link #REFERENCE}). A file name in CamelCase
 * is the other example's snake_case name: {@code FixedPlacements.java} is {@code fixed_placements}.
 *
 * <p>An example that still runs but now prints a different answer teaches a different thing, and
 * only a committed output notices that. The cross-language comparison keeps the answers themselves
 * -- score vectors, placements, refusal codes -- identical across languages. Lines that only name
 * the language or speak for this engine (a file name, a command, how a missing value or a boolean
 * is spelt, which engines agree) are the one permitted difference, and each is listed in
 * {@link #LANGUAGE_WORDING}. The one exception is two {@code reproducibility} lines, answers on a
 * simulated clock that this engine reads as often as Python does, not as PHP does; they are held
 * to Python's lines there. Nothing is masked: the examples print no elapsed times, versions or
 * backend names, which are the only text the shared example runner masks.
 */
class ExamplesTest {
    private static final Path EXAMPLES = Path.of("examples");
    private static final Path EXPECTED = Path.of("src", "test", "resources", "examples");
    private static final Path CLASSES = Path.of("target", "classes");
    private static final long TIMEOUT_SECONDS = 300;
    private static final Pattern CLASS_JAVADOC = Pattern.compile("/\\*\\*(.*?)\\*/\\s*public\\s+final\\s+class",
            Pattern.DOTALL);

    /** The reference language of each example whose reference is not PHP. */
    private static final Map<String, String> REFERENCE = Map.of("execution", "python", "intelligence", "python",
            "artifacts", "python", "rebalancing", "python");

    /** Per example, each Java line that differs from the reference only in naming the language, and that line. */
    private static final Map<String, Map<String, String>> LANGUAGE_WORDING = Map.of(
            "execution", Map.ofEntries(
                    // This port's text says "plan builder" and "engine" where the reference
                    // says "adapter", and its section 4 speaks for every engine instead of
                    // naming its JavaScript example.
                    Map.entry("  loading order from geometry the plan builder never sees, so without one it says",
                            "  loading order from geometry this adapter never sees, so without one it says"),
                    Map.entry("  plan builder re-derived behind your back.", "  adapter re-derived behind your back."),
                    Map.entry("4. One plan, every engine, the same bytes",
                            "4. One plan, four engines, the same bytes"),
                    Map.entry("  Hand the same *result* to any Packvium engine, this one included, and it emits",
                            "  Hand the same *result* to all four adapters and they emit the same bytes. That"),
                    Map.entry("  the same bytes. That is stricter than the packing contract, and it can be: a",
                            "  is stricter than the packing contract, and it can be: a plan is derived from a"),
                    Map.entry("  plan is derived from a result, so there is nothing left to differ about.",
                            "  result, so there is nothing left to differ about."),
                    Map.entry("  It does not follow that every engine packing the same *request* agrees. Python,",
                            "  It does not follow that four engines packing the same *request* agree. Python"),
                    Map.entry("  PHP, Java, .NET and Go return identical results at the same version; Rust and",
                            "  and PHP are held to identical placements and do produce this exact form; Rust"),
                    Map.entry("  JavaScript return a valid answer at or above the objective floor, so their plan",
                            "  and JavaScript are held to a valid answer at or above the objective floor, and"),
                    Map.entry("  for this request can be a different length. Whose packing is better is a",
                            "  `examples/execution.mjs` prints 1280 bytes here rather than 1877 for that"),
                    Map.entry("  question the plan never answers.",
                            "  reason. Whose packing is better is a question the plan never answers."),
                    Map.entry("  preserved: true", "  preserved: True"),
                    Map.entry("  the approved plan is untouched: true", "  the approved plan is untouched: True"),
                    Map.entry("  a lock the solve cannot honour -> preserved=false, 1 lock reported back",
                            "  a lock the solve cannot honour -> preserved=False, 1 lock reported back")),
            "intelligence", Map.of(
                    // Thin evidence is an empty Optional where Python returns None.
                    "  minimum_confidence=0.9 -> Optional.empty",
                    "  minimum_confidence=0.9 -> None",
                    "  met. `Optional.empty()` is the whole answer: no low-confidence proposal to weigh.",
                    "  met. `None` is the whole answer: there is no low-confidence proposal to weigh."),
            "artifacts", Map.of(
                    "  contains a script or an external resource: false",
                    "  contains a script or an external resource: False"),
            "revisions", Map.of(
                    "  revision 0, parent null",
                    "  revision 0, parent NULL"),
            "basic", Map.of(
                    "the full result as a plain array is what Serialization.java explores:",
                    "the full result as a plain array is what serialization.php explores:"),
            "serialization", Map.of(
                    // The refusal names its engine, and "Java" is one character longer than "PHP",
                    // so the example's 100-character excerpt ends one character earlier.
                    "  sending one anyway: unsupported_feature: the Java engine does not yet implement"
                            + " container.pallet_overhang_limit; the req",
                    "  sending one anyway: unsupported_feature: the PHP engine does not yet implement"
                            + " container.pallet_overhang_limit; the requ",
                    "the CLI takes exactly the document above:  echo '...' | java -jar target/packvium-1.7.0.jar",
                    "the CLI takes exactly the document above:  echo '...' | php bin/packvium"),
            "errors", Map.of(
                    // The reference names its own language's base exception.
                    "  Both are `IllegalArgumentException`s, so a handler that ends with",
                    "  Both are `InvalidArgumentException`s, so a handler that ends with",
                    "  `catch (IllegalArgumentException e)` still turns them into a 422 -- but neither",
                    "  `catch (InvalidArgumentException $e)` still turns them into a 422 -- but neither"),
            "rebalancing", Map.of(
                    "  still a valid packing: true", "  still a valid packing: True",
                    "  pallet#1 30 kg (10 items)  improved: false", "  pallet#1 30 kg (10 items)  improved: False"),
            "reproducibility", Map.of(
                    // Section 1 runs on a simulated clock that moves each time it is read, so
                    // its answers count clock reads. This engine reads the clock where Python
                    // does and prints Python's two lines here; PHP's 1.6 search reads it a
                    // different number of times.
                    "  busy host    medium left out  6  termination time_limit   replay not_guaranteed",
                    "  busy host    large  left out  0  termination complete     replay exact",
                    "  slower host  small  left out 12  termination time_limit   replay not_guaranteed",
                    "  slower host  large  left out  2  termination time_limit   replay not_guaranteed"),
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

        // Since JDK 18, System.out encodes with the console's code page (Cp1252 on a Windows
        // runner), which would turn the examples' dashes into bytes this test cannot read as
        // UTF-8. JDK 17 ignores stdout.encoding and follows file.encoding instead.
        Process process = new ProcessBuilder(javaBinary(), "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-cp", CLASSES.toString(), example.toString()).start();
        // Drained concurrently: a full stderr pipe would block the example before it exits.
        CompletableFuture<String> errors = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        String output = read(process.getInputStream());
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), example + " did not finish");
        assertEquals(0, process.exitValue(), example + " failed:\n" + errors.join());

        assertEquals(expected(name + ".out"), output, example + " drifted from its recorded output");
        String reference = REFERENCE.getOrDefault(name, "php");
        assertEquals(expected(reference + "/" + name + ".out"), inReferenceWording(name, output),
                example + " answers differently from the " + reference + " example");
    }

    private static String inReferenceWording(String name, String output) {
        Map<String, String> wording = LANGUAGE_WORDING.getOrDefault(name, Map.of());
        String[] lines = output.split("\n", -1);
        long replaced = 0;
        for (int index = 0; index < lines.length; index++) {
            String referenceLine = wording.get(lines[index]);
            if (referenceLine != null) {
                lines[index] = referenceLine;
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
        String name = file.substring(0, file.length() - ".java".length());
        return name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
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
