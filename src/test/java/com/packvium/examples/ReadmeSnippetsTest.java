package com.packvium.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles every Java snippet in README.md against the library, with the build's own
 * {@code -Xlint:all -Werror}.
 *
 * <p>A guide whose code no longer compiles teaches an API that does not exist, and nothing else
 * would notice: the examples are checked by {@link ExamplesTest}, the README's snippets only here.
 * Each snippet becomes one method body. A few read a variable the prose says the reader already
 * has -- a {@code request}, a commerce {@code document}, an execution {@code plan} -- and those
 * are declared once as fields. The last three snippets build on one another, as the README says,
 * so they share one method.
 */
class ReadmeSnippetsTest {
    private static final Path README = Path.of("README.md");
    private static final Path CLASSES = Path.of("target", "classes");
    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)```", Pattern.DOTALL);
    private static final int CHAINED = 3;

    @Test
    void everySnippetCompiles(@TempDir Path work) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = JAVA_BLOCK.matcher(Files.readString(README, StandardCharsets.UTF_8));
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        assertTrue(blocks.size() > CHAINED, "README.md has too few Java snippets to check");

        Path source = work.resolve("Snippets.java");
        Files.writeString(source, program(blocks), StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "no system Java compiler: the tests need a JDK");
        StringWriter diagnostics = new StringWriter();
        boolean compiled = compiler.getTask(diagnostics, null, null,
                List.of("-Xlint:all", "-Werror", "-classpath", CLASSES.toString(), "-d", work.toString()), null,
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8).getJavaFileObjects(source))
                .call();
        assertTrue(compiled, () -> "a README snippet does not compile:\n" + diagnostics);
        assertEquals("", diagnostics.toString().strip());
    }

    private static String program(List<String> blocks) {
        TreeSet<String> imports = new TreeSet<>(List.of("import com.packvium.serialization.JsonReader;",
                "import java.util.Map;"));
        List<String> bodies = new ArrayList<>();
        for (String block : blocks) {
            StringBuilder body = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("import ")) {
                    imports.add(line);
                } else {
                    body.append(line).append('\n');
                }
            }
            bodies.add(body.toString());
        }
        StringBuilder program = new StringBuilder(String.join("\n", imports)).append("\n\n");
        program.append("@SuppressWarnings(\"unchecked\")\nfinal class Snippets {\n")
                .append("    static Map<String, Object> request = (Map<String, Object>) JsonReader.parse(\"{}\");\n")
                .append("    static Map<String, Object> document = Map.of();\n")
                .append("    static Map<String, Object> plan = Map.of();\n");
        int standalone = bodies.size() - CHAINED;
        for (int index = 0; index < standalone; index++) {
            program.append("    static void snippet").append(index).append("() throws Exception {\n")
                    .append(bodies.get(index)).append("    }\n");
        }
        program.append("    static void chained() throws Exception {\n")
                .append(String.join("", bodies.subList(standalone, bodies.size()))).append("    }\n}\n");
        return program.toString();
    }
}
