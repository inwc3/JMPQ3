package systems.crigges.jmpq3test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import systems.crigges.jmpq.MpqArchive;
import systems.crigges.jmpq.MpqFileEntry;
import systems.crigges.jmpq.MpqOpenOptions;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The new core's read side, held to the same golden manifest as the old one.
 * <p>
 * This is what makes the Phase 1 rewrite verifiable rather than hopeful: the
 * manifest was produced by {@code tools/mpqref.py}, an independent
 * implementation, so agreeing with it means the new reader understands the
 * format, not merely that it agrees with its predecessor. The last test also
 * compares the two implementations directly, which catches a divergence in
 * codecs the reference cannot decode.
 */
public class MpqArchiveGoldenTests {
    private static final Logger log = LoggerFactory.getLogger(MpqArchiveGoldenTests.class);

    private GoldenManifest manifest;

    @BeforeClass
    public void loadManifest() {
        manifest = new GoldenManifest("golden/fixtures.tsv");
    }

    /** Content must match the independent reference byte for byte. */
    @Test
    public void readMatchesIndependentReference() throws IOException {
        int compared = 0;
        final List<String> problems = new ArrayList<>();

        for (Map.Entry<String, List<GoldenManifest.Entry>> fixture : manifest.byArchive().entrySet()) {
            final Path path = TestResources.mpqCopy(fixture.getKey());
            try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
                for (GoldenManifest.Entry expected : fixture.getValue()) {
                    if (!expected.hasDigest()) {
                        continue;
                    }
                    if (!archive.contains(expected.name())) {
                        problems.add(fixture.getKey() + ": cannot find " + expected.name());
                        continue;
                    }
                    final byte[] actual = archive.read(expected.name());
                    if (actual.length != expected.size()) {
                        problems.add(fixture.getKey() + ": " + expected.name() + " size "
                            + actual.length + ", reference says " + expected.size());
                    } else if (!TestHelper.md5(actual).equals(expected.md5())) {
                        problems.add(fixture.getKey() + ": " + expected.name()
                            + " content differs from the reference");
                    } else {
                        compared++;
                    }
                }
            }
        }

        assertNoProblems(problems, "the new core disagrees with the reference");
        Assert.assertTrue(compared >= 150, "only compared " + compared + " files");
        log.info("new core verified {} files against the independent reference", compared);
    }

    /** Every file the reference located must be visible, whatever its codec. */
    @Test
    public void everyReferenceFileIsVisible() throws IOException {
        final List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<GoldenManifest.Entry>> fixture : manifest.byArchive().entrySet()) {
            final Path path = TestResources.mpqCopy(fixture.getKey());
            try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
                for (GoldenManifest.Entry expected : fixture.getValue()) {
                    if (!archive.contains(expected.name())) {
                        missing.add(fixture.getKey() + ": " + expected.name());
                    }
                }
            }
        }
        assertNoProblems(missing, "files the reference found but the new core cannot see");
    }

    /**
     * The old and new implementations must agree on every file, including those
     * using codecs the reference does not implement. Without this, a shared
     * misreading of PKWARE or ADPCM data would go unnoticed by both.
     */
    @Test
    public void newCoreAgreesWithTheCompatibilityLayer() throws IOException {
        int compared = 0;
        final List<String> problems = new ArrayList<>();

        for (String fixture : manifest.byArchive().keySet()) {
            final Path path = TestResources.mpqCopy(fixture);

            final Map<String, String> viaOldCore = new java.util.LinkedHashMap<>();
            try (JMpqEditor editor =
                     new JMpqEditor(path, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                final List<String> names = new ArrayList<>(editor.getFileNames());
                for (String internal : List.of("(listfile)", "(attributes)", "(signature)")) {
                    if (editor.hasFile(internal) && !names.contains(internal)) {
                        names.add(internal);
                    }
                }
                for (String name : names) {
                    if (!editor.hasFile(name)) {
                        continue;
                    }
                    try {
                        viaOldCore.put(name, TestHelper.md5(editor.extractFileAsBytes(name)));
                    } catch (IOException e) {
                        // Undecodable by both is fine; recorded as absent.
                        log.debug("old core cannot decode {} in {}", name, fixture, e);
                    }
                }
            }

            try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
                for (Map.Entry<String, String> expected : viaOldCore.entrySet()) {
                    if (!archive.contains(expected.getKey())) {
                        problems.add(fixture + ": new core cannot see " + expected.getKey());
                        continue;
                    }
                    final String actual = TestHelper.md5(archive.read(expected.getKey()));
                    if (!actual.equals(expected.getValue())) {
                        problems.add(fixture + ": " + expected.getKey() + " differs between cores");
                    } else {
                        compared++;
                    }
                }
            }
        }

        assertNoProblems(problems, "the two implementations disagree");
        Assert.assertTrue(compared >= 170, "only compared " + compared + " files across cores");
        log.info("new and old cores agree on {} files", compared);
    }

    /** Streaming a file must produce the same bytes as reading it whole. */
    @Test
    public void streamingMatchesWholeFileRead() throws IOException {
        Path path = TestResources.mpqCopy("normalMap");
        try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
            for (String name : archive.names()) {
                final byte[] whole = archive.read(name);
                final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
                archive.readTo(name, streamed);
                Assert.assertEquals(streamed.toByteArray(), whole, name);
            }
        }
    }

    /** Walking the block table must find every live block, named or not. */
    @Test
    public void entriesCoverEveryLiveBlock() throws IOException {
        for (String fixture : manifest.byArchive().keySet()) {
            final Path path = TestResources.mpqCopy(fixture);
            try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
                final List<MpqFileEntry> entries = archive.entries();
                Assert.assertEquals(entries.size(), archive.blockCount(), fixture);
                for (MpqFileEntry entry : entries) {
                    Assert.assertTrue(entry.exists(), fixture + " listed a dead block");
                }
                // Named entries must be readable by their name.
                for (MpqFileEntry entry : entries) {
                    if (!entry.name().isEmpty()) {
                        Assert.assertTrue(archive.contains(entry.name()),
                            fixture + ": " + entry.name() + " is named but not findable");
                    }
                }
            }
        }
    }

    /** An archive with no list file is still readable by exact name. */
    @Test
    public void listfilelessArchiveIsReadableByName() throws IOException {
        Path path = TestResources.mpqCopy("listfilelessMap");
        try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.names().isEmpty(),
                "an archive without a listfile should not enumerate");
            // The reference located these by brute force against the shipped
            // default listfile, so they must be readable by exact name.
            final List<GoldenManifest.Entry> expected = manifest.forArchive("listfilelessMap.w3x");
            int found = 0;
            for (GoldenManifest.Entry entry : expected) {
                if (archive.contains(entry.name())) {
                    found++;
                    if (entry.hasDigest()) {
                        Assert.assertEquals(TestHelper.md5(archive.read(entry.name())), entry.md5(),
                            entry.name());
                    }
                }
            }
            Assert.assertTrue(found > 10, "expected to reach most files by name, reached " + found);
        }
    }

    /** Opening a file-backed archive must not keep it locked after close. */
    @Test
    public void closingReleasesTheFile() throws IOException {
        Path path = TestResources.mpqCopy("normalMap");
        try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.names().isEmpty());
        }
        Files.delete(path);
        Assert.assertFalse(Files.exists(path));
    }

    /** Reading an absent file must say so rather than return nothing. */
    @Test
    public void absentFileIsReported() throws IOException {
        Path path = TestResources.mpqCopy("normalMap");
        try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.contains("does\\not\\exist.txt"));
            Assert.assertTrue(archive.entry("does\\not\\exist.txt").isEmpty());
            Assert.expectThrows(IOException.class, () -> archive.read("does\\not\\exist.txt"));
        }
    }

    /** In-memory archives read identically to file-backed ones. */
    @Test
    public void inMemoryArchiveReadsTheSame() throws IOException {
        Path path = TestResources.mpqCopy("normalMap");
        final byte[] image = Files.readAllBytes(path);

        try (MpqArchive viaFile = MpqArchive.open(path, MpqOpenOptions.warcraft3());
             MpqArchive viaArray = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(viaArray.names(), viaFile.names());
            for (String name : viaFile.names()) {
                Assert.assertEquals(viaArray.read(name), viaFile.read(name), name);
            }
        }
        // Wrapping must not have modified the caller's array.
        Assert.assertEquals(image, Files.readAllBytes(path));
    }

    private static void assertNoProblems(List<String> problems, String what) {
        if (!problems.isEmpty()) {
            Assert.fail(what + " (" + problems.size() + "):" + System.lineSeparator()
                + String.join(System.lineSeparator(), problems));
        }
    }
}
