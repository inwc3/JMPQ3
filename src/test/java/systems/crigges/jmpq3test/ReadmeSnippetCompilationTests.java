package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles the Java blocks in {@code Readme.md} exactly as they are written.
 *
 * <h2>Why this exists on top of {@link ReadmeExampleTests}</h2>
 * That class runs the same code and so keeps the readme honest about
 * <em>behaviour</em>. It cannot keep it honest about being copy-pasteable,
 * because the imports live on the test class rather than in the snippet — which
 * is exactly how a snippet referencing {@code Files}, {@code Path} and
 * {@code StandardCharsets} with none of them imported passed review here and
 * would have failed for the first person to copy it.
 * <p>
 * So this compiles the text. A block that presents itself as complete, by
 * carrying at least one {@code import}, has to compile with only the imports it
 * declares. Blocks with no imports are fragments — the options builder, the
 * two-line integrity examples — and are not compiled, because they legitimately
 * reference variables the surrounding prose introduces.
 */
public class ReadmeSnippetCompilationTests {

    /** Fewer than this and the extraction has silently stopped working. */
    private static final int MINIMUM_COMPLETE_SNIPPETS = 3;

    /** A Java block lifted out of the readme. */
    private record Snippet(int number, int line, String imports, String body) {
        boolean isComplete() {
            return !imports.isBlank();
        }
    }

    @Test
    public void everyCompleteReadmeSnippetCompiles() throws IOException {
        final Path readme = Path.of("Readme.md");
        Assert.assertTrue(Files.exists(readme), "run from the project root; looked for " + readme);

        final List<Snippet> snippets = extract(Files.readString(readme));
        final List<Snippet> complete = snippets.stream().filter(Snippet::isComplete).toList();

        Assert.assertTrue(complete.size() >= MINIMUM_COMPLETE_SNIPPETS,
            "only found " + complete.size() + " self-contained snippets in the readme,"
                + " which suggests the extraction below stopped matching its formatting");

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assert.assertNotNull(compiler, "no system Java compiler; run tests on a JDK");

        for (Snippet snippet : complete) {
            final String className = "ReadmeSnippet" + snippet.number();
            final String source = snippet.imports()
                + "\npublic class " + className + " {\n"
                + "    @SuppressWarnings(\"unused\")\n"
                + "    void run() throws Exception {\n"
                + snippet.body()
                + "\n    }\n}\n";

            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            final boolean compiled = compile(compiler, className, source, diagnostics);

            if (!compiled) {
                final StringBuilder message = new StringBuilder("Readme.md snippet #")
                    .append(snippet.number()).append(" (line ").append(snippet.line())
                    .append(") does not compile as written:\n");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        message.append("  ").append(diagnostic.getMessage(null)).append('\n');
                    }
                }
                Assert.fail(message.toString());
            }
        }
    }

    /**
     * Splits each fenced {@code java} block into its imports and the rest.
     * <p>
     * The imports have to be hoisted above the generated class declaration,
     * which is the only rearranging done here — everything else is compiled
     * verbatim, so a missing import stays missing.
     */
    private static List<Snippet> extract(String markdown) {
        final List<Snippet> snippets = new ArrayList<>();
        final String[] lines = markdown.split("\n", -1);

        int number = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].strip().equals("```java")) {
                continue;
            }
            final int startLine = i + 2;
            final StringBuilder imports = new StringBuilder();
            final StringBuilder body = new StringBuilder();

            for (i++; i < lines.length && !lines[i].strip().equals("```"); i++) {
                if (lines[i].startsWith("import ")) {
                    imports.append(lines[i]).append('\n');
                } else {
                    body.append("        ").append(lines[i]).append('\n');
                }
            }
            snippets.add(new Snippet(++number, startLine, imports.toString(), body.toString()));
        }
        return snippets;
    }

    private static boolean compile(JavaCompiler compiler, String className, String source,
                                   DiagnosticCollector<JavaFileObject> diagnostics)
        throws IOException {
        final JavaFileObject unit = new SimpleJavaFileObject(
            URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        final Path output = Files.createTempDirectory("readme-snippets");
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
            // The snippets reference this library, so they need the same
            // classpath the tests run with.
            final List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"));
            return compiler.getTask(null, files, diagnostics, options, null, List.of(unit)).call();
        } finally {
            try (var walk = Files.walk(output)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A leftover temp file is not worth failing a test over.
                    }
                });
            }
        }
    }
}
