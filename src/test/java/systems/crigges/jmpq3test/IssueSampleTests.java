package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqOpenOptions;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The real maps named in the issue tracker.
 *
 * <h2>Why these are opt-in</h2>
 * Two issues name a specific third-party map as the thing that must work:
 * {@code #46} points at Forest Defense 0.21g and {@code #47} at Green TD Pro
 * Deathmatch. Those maps are other people's work, so they are not committed
 * here; a fixture directory is pointed at instead, and these tests skip when it
 * is absent.
 * <p>
 * That is a real limitation and worth stating plainly: CI does not run these,
 * so the synthetic fixtures elsewhere are what protects the behaviour
 * day to day. What these add is the one thing synthetic fixtures cannot — that
 * the actual archives from the actual bug reports actually open.
 *
 * <h2>Running them</h2>
 * <pre>
 * ./gradlew test -PissueSamples=/path/to/maps
 * </pre>
 * That is a Gradle <em>project</em> property. {@code -D} sets a system property
 * on Gradle's own JVM rather than on the forked test JVM, so it would leave these
 * tests skipping while looking like they had run — the build forwards it too, for
 * exactly that reason, but the line above is the one to reach for.
 * <p>
 * Any {@code .w3x} or {@code .mpq} in that directory is opened and every file it
 * can name is extracted. Maps whose names are recognised get their specific
 * pathology asserted as well.
 */
public class IssueSampleTests {

    /** Directory holding the sample maps, if the runner supplied one. */
    private static final String PROPERTY = "jmpq3.issueSamples";

    /**
     * Samples that need protection-specific handling this library does not do.
     * <p>
     * Green TD Pro Deathmatch, from issue #47, is the case the audit had in mind
     * when it marked fake-header resilience best-effort and said to skip it if it
     * destabilised normal parsing. It needs four behaviours, none of them
     * general:
     * <ol>
     *   <li>Two decoy user data headers whose redirects point at bytes that are
     *       not archive headers.</li>
     *   <li>A header declaring format version 2 whose hi-word fields are junk,
     *       so it is only readable if the declared version is ignored.</li>
     *   <li>Table offsets relying on 32-bit wraparound: the block table is
     *       declared at {@code 0xFFFFF220}, landing inside the file only because
     *       Storm.dll adds it to the header offset in 32-bit arithmetic
     *       ({@code 0x1400 - 3552 = 0x620}).</li>
     *   <li>Every block declaring a compressed size of {@code 0xFFFFFFFF}, so
     *       stored sizes have to be inferred from neighbouring positions.</li>
     * </ol>
     * Diagnosed rather than guessed: at those wrapped offsets the tables do
     * decode, every known Warcraft III path resolves in the hash table, and the
     * block positions ascend sensibly. Supporting it is a deliberate non-goal.
     * <p>
     * What is still required of such a sample is that it fails cleanly, with
     * this library's own exception rather than a crash or silently wrong data.
     */
    private static final List<String> NEEDS_PROTECTION_SUPPORT = List.of("greentd");

    private static List<Path> samples() {
        final String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new SkipException("Run with -PissueSamples=<dir> to test against the maps"
                + " named in issues #46 and #47. They are third-party maps and are not"
                + " committed to this repository.");
        }
        final Path directory = Path.of(configured);
        if (!Files.isDirectory(directory)) {
            throw new SkipException(PROPERTY + " is not a directory: " + directory);
        }
        try (var entries = Files.list(directory)) {
            final List<Path> found = entries
                .filter(Files::isRegularFile)
                .filter(path -> {
                    final String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".w3x") || name.endsWith(".w3m") || name.endsWith(".mpq");
                })
                .sorted()
                .toList();
            if (found.isEmpty()) {
                throw new SkipException("No archives in " + directory);
            }
            return found;
        } catch (IOException e) {
            throw new SkipException("Cannot list " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Every sample opens without {@code FORCE_V0}.
     * <p>
     * That is the substance of issue #46. The old reader rejected a declared
     * header size outside 32 to 208 outright, and protected maps carry garbage
     * there — Forest Defense declares 2097410. Needing {@code FORCE_V0} to work
     * around it was the symptom; not needing it is the fix.
     */
    @Test
    public void everySampleOpensWithoutForcingVersion0() throws IOException {
        for (Path sample : supported()) {
            try (MpqArchive archive = MpqArchive.open(sample, MpqOpenOptions.defaults())) {
                Assert.assertTrue(archive.blockCount() > 0,
                    sample.getFileName() + " opened but has no blocks");
            }
        }
    }

    /**
     * Whatever a sample can name, it can extract, and both open modes agree on
     * every byte.
     * <p>
     * A protected map typically has no usable list file, so {@code names()} may
     * be empty and nothing here requires otherwise. Extraction is still checked
     * against known Warcraft III paths, which is how such a map is recovered in
     * practice.
     */
    @Test
    public void everySampleExtractsWhatItCanName() throws IOException {
        final List<String> knownPaths = List.of(
            "war3map.j", "war3map.w3i", "war3map.w3e", "war3map.wts", "war3map.doo",
            "war3map.shd", "war3map.wpm", "war3mapMap.blp", "war3mapExtra.txt");

        for (Path sample : supported()) {
            final List<String> recovered = new ArrayList<>();
            try (MpqArchive trusted = MpqArchive.open(sample, MpqOpenOptions.defaults());
                 MpqArchive forced = MpqArchive.open(sample, MpqOpenOptions.warcraft3())) {

                for (String name : trusted.names()) {
                    Assert.assertEquals(trusted.read(name), forced.read(name),
                        sample.getFileName() + " / " + name + " differs between open modes");
                    recovered.add(name);
                }

                // Protected maps: nothing is enumerable, but the content is
                // still there for a caller who knows the path.
                for (String probe : knownPaths) {
                    if (trusted.contains(probe)) {
                        final byte[] content = trusted.read(probe);
                        Assert.assertTrue(content.length > 0,
                            sample.getFileName() + " / " + probe + " decoded to nothing");
                        Assert.assertEquals(content, forced.read(probe),
                            sample.getFileName() + " / " + probe + " differs between open modes");
                        recovered.add(probe);
                    }
                }

                Assert.assertFalse(recovered.isEmpty(),
                    sample.getFileName() + " yielded no files at all, by name or by list file");
            }
        }
    }

    /**
     * A sample this library cannot read must say so, not misbehave.
     * <p>
     * The distinction matters more than the support does: a protected map that
     * throws is one a caller can handle, while one returning plausible rubbish
     * corrupts whatever is built from it.
     */
    @Test
    public void unsupportedSamplesFailCleanly() {
        for (Path sample : samples()) {
            if (!needsProtectionSupport(sample)) {
                continue;
            }
            final systems.crigges.jmpq3.JMpqException thrown = Assert.expectThrows(
                systems.crigges.jmpq3.JMpqException.class,
                () -> MpqArchive.open(sample, MpqOpenOptions.defaults()).close());
            Assert.assertNotNull(thrown.getMessage(),
                sample.getFileName() + " failed without saying why");
        }
    }

    private static boolean needsProtectionSupport(Path sample) {
        final String name = sample.getFileName().toString().toLowerCase();
        return NEEDS_PROTECTION_SUPPORT.stream().anyMatch(name::contains);
    }

    /** The samples this library claims to handle. */
    private static List<Path> supported() {
        final List<Path> usable = samples().stream()
            .filter(sample -> !needsProtectionSupport(sample))
            .toList();
        if (usable.isEmpty()) {
            throw new SkipException("every sample present needs protection-specific handling");
        }
        return usable;
    }

    /**
     * Forest Defense from issue #46, if present: the header size really is
     * garbage, and the archive really does open anyway.
     */
    @Test
    public void forestDefenseHeaderIsRepairedRatherThanRejected() throws IOException {
        final Path sample = samples().stream()
            .filter(path -> path.getFileName().toString().toLowerCase().contains("forest"))
            .findFirst()
            .orElseThrow(() -> new SkipException("Forest Defense not among the samples"));

        try (MpqArchive archive = MpqArchive.open(sample, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.header().malformed(),
                "the whole point is that this header needs repair");
            Assert.assertEquals(archive.header().headerSize(), 32,
                "repaired to the size its version implies");
            Assert.assertTrue(archive.contains("war3map.j"), "the map script must be reachable");
            Assert.assertTrue(archive.read("war3map.j").length > 0);
        }
    }
}
