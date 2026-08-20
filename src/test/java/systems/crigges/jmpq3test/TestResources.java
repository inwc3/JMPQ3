package systems.crigges.jmpq3test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Test-only access to classpath fixtures.
 * <p>
 * Fixtures are never used in place: every accessor copies the resource into a
 * scratch directory below {@code java.io.tmpdir} using streams only. That
 * matters for three reasons (P4-2):
 * <ul>
 * <li>{@code getResource().getFile()} mangles any path containing spaces or
 * other characters that need URL escaping, and returns something unusable when
 * the resources live inside a jar.</li>
 * <li>Tests that mutate archives must not mutate {@code build/resources},
 * because the next test run would then start from modified input.</li>
 * <li>Writable fixtures must be isolated per call so tests can run in any
 * order, and concurrently.</li>
 * </ul>
 * Everything handed out is deleted on JVM exit.
 */
public final class TestResources {
    /** Root scratch directory shared by all tests of a single JVM run. */
    private static final Path ROOT = createRoot();

    /** Distinguishes scratch directories handed out by {@link #scratchDir}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestResources() {
    }

    private static Path createRoot() {
        try {
            final Path root = Files.createTempDirectory("jmpq3-tests");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(root)));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create test scratch directory.", e);
        }
    }

    /**
     * Creates an empty scratch directory that no other caller shares.
     *
     * @param hint short name fragment to make the directory recognisable.
     * @return path to a new, empty, writable directory.
     */
    public static Path scratchDir(String hint) {
        try {
            final Path dir = ROOT.resolve(hint + "-" + SEQUENCE.incrementAndGet());
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create scratch directory for " + hint, e);
        }
    }

    /**
     * Copies a single classpath resource into a fresh scratch directory.
     *
     * @param resource resource path relative to the classpath root, e.g.
     *                 {@code "mpqs/normalMap.w3x"}.
     * @return the writable copy, named after the resource's file name.
     */
    public static Path file(String resource) {
        final String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        final Path target = scratchDir(sanitise(fileName)).resolve(fileName);
        try (InputStream in = open(resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy resource " + resource, e);
        }
        return target;
    }

    /**
     * Reads a classpath resource without materialising it on disk.
     *
     * @param resource resource path relative to the classpath root.
     * @return the resource content.
     */
    public static byte[] bytes(String resource) {
        try (InputStream in = open(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read resource " + resource, e);
        }
    }

    /**
     * Copies a classpath resource directory, recursively, into a fresh scratch
     * directory.
     *
     * @param resource resource directory relative to the classpath root.
     * @return path to the writable copy of the directory.
     */
    public static Path directory(String resource) {
        final Path target = scratchDir(sanitise(resource));
        walk(resource, (relative, in) -> {
            final Path destination = target.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        });
        return target;
    }

    /**
     * Copies every MPQ archive fixture into a fresh scratch directory.
     * <p>
     * Each returned archive is a private, writable copy, so callers are free to
     * rebuild them.
     *
     * @return writable copies of all archives in {@code mpqs/}.
     */
    public static List<Path> mpqCopies() {
        final Path dir = directory("mpqs");
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(TestResources::isArchive).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list archive fixtures.", e);
        }
    }

    /**
     * Copies a single MPQ archive fixture whose file name contains the given
     * fragment.
     *
     * @param nameFragment fragment of the fixture file name, e.g.
     *                     {@code "normalMap"}.
     * @return a writable copy of the matching archive.
     */
    public static Path mpqCopy(String nameFragment) {
        return file("mpqs/" + archiveFileName(nameFragment));
    }

    private static String archiveFileName(String nameFragment) {
        final List<String> matches = new ArrayList<>();
        walk("mpqs", (relative, in) -> {
            if (relative.contains(nameFragment)) {
                matches.add(relative);
            }
        });
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                "Expected exactly one archive fixture matching '" + nameFragment + "', found " + matches);
        }
        return matches.getFirst();
    }

    private static boolean isArchive(Path path) {
        final String name = path.getFileName().toString();
        return name.endsWith(".w3x") || name.endsWith(".mpq") || name.endsWith(".scx") || name.endsWith(".w3m");
    }

    private static InputStream open(String resource) throws IOException {
        final InputStream in = TestResources.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("No such test resource: " + resource);
        }
        return in;
    }

    /**
     * Visits every file below a classpath resource directory.
     * <p>
     * Works for both exploded directories and jar entries by mounting the
     * containing archive as a {@link FileSystem} when needed.
     */
    private static void walk(String resource, ResourceVisitor visitor) {
        final URL url = TestResources.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new IllegalArgumentException("No such test resource directory: " + resource);
        }
        try {
            final URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try (FileSystem fs = mount(uri)) {
                    walk(fs.provider().getPath(uri), visitor);
                }
            } else {
                walk(Path.of(uri), visitor);
            }
        } catch (URISyntaxException | IOException e) {
            throw new UncheckedIOException(new IOException("Cannot walk test resource " + resource, e));
        }
    }

    private static FileSystem mount(URI uri) throws IOException {
        try {
            return FileSystems.newFileSystem(uri, Map.of());
        } catch (FileSystemAlreadyExistsException e) {
            // Another walk already mounted this jar; reuse it and do not close it.
            return FileSystems.getFileSystem(uri);
        }
    }

    private static void walk(Path root, ResourceVisitor visitor) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                // Normalise to '/' so callers see the same relative names on
                // every platform and for both jar and directory layouts.
                final String relative = root.relativize(path).toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(path)) {
                    visitor.visit(relative, in);
                }
            }
        }
    }

    private static String sanitise(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp file must never fail a build.
                }
            });
        } catch (IOException ignored) {
            // Best effort, see above.
        }
    }

    /** Callback for {@link #walk(String, ResourceVisitor)}. */
    private interface ResourceVisitor {
        void visit(String relativeName, InputStream content) throws IOException;
    }
}
