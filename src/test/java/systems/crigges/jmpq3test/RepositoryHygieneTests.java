package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks on the repository itself, for damage the compiler cannot see.
 *
 * <h2>Why this exists</h2>
 * Merge conflict markers were committed and pushed in {@code AUDIT.md} and
 * {@code Readme.md}. The build stayed green throughout, because markdown is not
 * compiled and 225 passing tests say nothing about it — so every check that was
 * being run reported success on a broken tree.
 * <p>
 * The direct cause was reading {@code git merge} output through {@code tail},
 * which hid two of the four conflicted files. The lesson is not "read the output
 * more carefully" though: it is that a mechanical property should be checked
 * mechanically. Anything a reviewer would call obviously broken belongs here,
 * where it fails the build rather than depending on someone noticing.
 */
public class RepositoryHygieneTests {

    /** Directories with nothing to check, or too much to be worth walking. */
    private static final Set<String> SKIP_DIRECTORIES =
        Set.of(".git", ".gradle", "build", "out", ".idea", "bin");

    /** Only text worth scanning; binary fixtures cannot hold a stray marker. */
    private static final List<String> TEXT_SUFFIXES = List.of(
        ".java", ".md", ".gradle", ".yml", ".yaml", ".txt", ".py", ".tsv",
        ".xml", ".properties", ".json", ".gitattributes", ".gitignore");

    /**
     * Conflict markers at the start of a line. Split so this file cannot match
     * itself, which would be a slightly embarrassing way to fail.
     */
    private static final List<String> MARKERS = List.of(
        "<<<<<<" + "<", "======" + "=", ">>>>>>" + ">");

    private static Path repositoryRoot() {
        // Tests run with the project directory as the working directory.
        final Path root = Path.of("").toAbsolutePath();
        Assert.assertTrue(Files.exists(root.resolve("build.gradle")),
            "expected to run from the project root, but " + root + " has no build.gradle");
        return root;
    }

    private static boolean isText(Path path) {
        final String name = path.getFileName().toString().toLowerCase();
        return TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    @Test
    public void noFileContainsMergeConflictMarkers() throws IOException {
        final Path root = repositoryRoot();
        final List<String> offences = new ArrayList<>();
        final int[] scanned = {0};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return SKIP_DIRECTORIES.contains(directory.getFileName().toString())
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isText(file)) {
                    return FileVisitResult.CONTINUE;
                }
                scanned[0]++;
                final List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (IOException unreadable) {
                    // Not text after all; nothing to check.
                    return FileVisitResult.CONTINUE;
                }
                for (int i = 0; i < lines.size(); i++) {
                    final String line = lines.get(i);
                    for (String marker : MARKERS) {
                        if (line.startsWith(marker)) {
                            offences.add(root.relativize(file) + ":" + (i + 1) + " " + marker);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Assert.assertTrue(scanned[0] > 50,
            "only scanned " + scanned[0] + " files, so this check is not looking where it should");
        Assert.assertTrue(offences.isEmpty(),
            "unresolved merge conflict markers:\n  " + String.join("\n  ", offences));
    }
}
