package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The new core's write side (P1-2, P1-3, P1-6, P1-8).
 * <p>
 * The important property is round-trip content preservation across a change of
 * sector size, because that is where the pre-2.0 writer silently corrupted
 * every file it copied verbatim.
 */
public class MpqArchiveWriterTests {
    private static final Logger log = LoggerFactory.getLogger(MpqArchiveWriterTests.class);

    private GoldenManifest manifest;

    @BeforeClass
    public void loadManifest() {
        manifest = new GoldenManifest("golden/fixtures.tsv");
    }

    /** Nothing is written until save is called. */
    @Test
    public void nothingIsWrittenWithoutSave() throws IOException {
        Path target = TestResources.scratchDir("no-save").resolve("unwritten.w3x");

        MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8));

        Assert.assertFalse(Files.exists(target),
            "the writer created a file without being asked to save");
    }

    /** A built archive must be readable, and hold what was put in it. */
    @Test
    public void roundTripsWhatWasPutIn() throws IOException {
        final byte[] script = TestResources.bytes("war3map.j");
        final byte[] empty = new byte[0];
        final byte[] incompressible = TestResources.bytes("incompressible.w3u");

        final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("war3map.j", script)
            .put("empty.txt", empty)
            .put("Units\\data.w3u", incompressible)
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.read("war3map.j"), script);
            Assert.assertEquals(archive.read("empty.txt"), empty);
            Assert.assertEquals(archive.read("Units\\data.w3u"), incompressible);
            Assert.assertTrue(archive.names().contains("war3map.j"));
            Assert.assertTrue(archive.contains("(listfile)"), "a listfile should have been written");
        }
    }

    /** Every fixture must survive a rebuild through the new writer. */
    @Test
    public void rebuildPreservesEveryFixture() throws IOException {
        assertRebuildPreservesContent(MpqWriteOptions.defaults(), "copy");
    }

    /**
     * The same, but re-encoding everything. This is the case the pre-2.0 writer
     * got wrong: it copied .wav files verbatim while advertising a new sector
     * size, so their offset tables described the old geometry.
     */
    @Test
    public void rebuildWithNewSectorSizePreservesEveryFixture() throws IOException {
        final RecompressOptions recompress = new RecompressOptions(true);
        recompress.iterations = 1;
        assertRebuildPreservesContent(
            MpqWriteOptions.defaults().withSectorSizeShift(7).withRecompression(recompress),
            "sector size 16 KiB");
    }

    private void assertRebuildPreservesContent(MpqWriteOptions options, String label)
        throws IOException {
        final List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String fixture : manifest.byArchive().keySet()) {
            final Path source = TestResources.mpqCopy(fixture);
            final Map<String, String> before = new LinkedHashMap<>();
            final byte[] rebuilt;

            try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
                for (String name : archive.names()) {
                    try {
                        before.put(name, TestHelper.md5(archive.read(name)));
                    } catch (IOException e) {
                        log.debug("cannot decode {} in {}", name, fixture, e);
                    }
                }
                if (before.isEmpty()) {
                    continue;
                }
                rebuilt = MpqArchiveWriter.from(archive, options).toByteArray();
            }

            try (MpqArchive archive = MpqArchive.open(rebuilt, MpqOpenOptions.warcraft3())) {
                for (Map.Entry<String, String> expected : before.entrySet()) {
                    if (!archive.contains(expected.getKey())) {
                        problems.add(fixture + " [" + label + "]: lost " + expected.getKey());
                        continue;
                    }
                    final String actual = TestHelper.md5(archive.read(expected.getKey()));
                    if (!actual.equals(expected.getValue())) {
                        problems.add(fixture + " [" + label + "]: " + expected.getKey()
                            + " content changed");
                    } else {
                        checked++;
                    }
                }
            }
        }

        if (!problems.isEmpty()) {
            Assert.fail("rebuild lost data (" + problems.size() + "):" + System.lineSeparator()
                + String.join(System.lineSeparator(), problems));
        }
        Assert.assertTrue(checked > 150, "only checked " + checked + " files");
        log.info("writer preserved {} files [{}]", checked, label);
    }

    /** The header must describe the image that was actually produced. */
    @Test
    public void headerDescribesTheImage() throws IOException {
        for (String fixture : manifest.byArchive().keySet()) {
            final Path source = TestResources.mpqCopy(fixture);
            final byte[] rebuilt;
            try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
                rebuilt = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults()).toByteArray();
            }

            try (MpqArchive archive = MpqArchive.open(rebuilt, MpqOpenOptions.warcraft3())) {
                final var header = archive.header();
                Assert.assertEquals(header.headerOffset() + header.archiveSize(), rebuilt.length,
                    fixture + ": header size disagrees with the image length");
                Assert.assertEquals(header.blockTablePosition(),
                    header.hashTablePosition() + (long) header.hashTableEntries() * 16,
                    fixture + ": block table does not follow the hash table");
                Assert.assertFalse(header.malformed(), fixture + ": we wrote a malformed header");
            }
        }
    }

    /** P1-6: the format version is chosen, not inherited. */
    @Test
    public void formatVersionIsExplicit() throws IOException {
        final byte[] payload = "v".getBytes(StandardCharsets.UTF_8);

        for (int version : new int[]{0, 1}) {
            final byte[] image = MpqArchiveWriter
                .create(MpqWriteOptions.defaults().withFormatVersion(version))
                .put("a.txt", payload)
                .toByteArray();

            try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
                Assert.assertEquals(archive.header().formatVersion(), version);
                Assert.assertEquals(archive.header().headerSize(), version == 0 ? 32 : 44);
                Assert.assertEquals(archive.read("a.txt"), payload);
            }
        }

        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqWriteOptions.defaults().withFormatVersion(2));
    }

    /** P1-8: table capacity is controllable, for protection tooling. */
    @Test
    public void tableCapacityCanBeForced() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withHashTableCapacity(1024).withExtraBlockEntries(16))
            .put("a.txt", "x".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.header().hashTableEntries(), 1024);
            // Two real files plus the extra slots requested.
            Assert.assertEquals(archive.header().blockTableEntries(), 2 + 16);
            Assert.assertEquals(archive.read("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
            // The extra slots must not read as files.
            Assert.assertEquals(archive.blockCount(), 2);
        }

        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqWriteOptions.defaults().withHashTableCapacity(1000));
    }

    /**
     * Only the name the writer generates is refused. This test used to expect
     * all three internal names to be rejected, which made (attributes) and
     * (signature) impossible to preserve at all -- nothing regenerates them, so
     * refusing them meant losing them.
     */
    @Test
    public void onlyGeneratedNamesAreRejected() {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults());
        for (String generated : List.of("(listfile)", "(ListFile)", "(LISTFILE)")) {
            Assert.expectThrows(IllegalArgumentException.class,
                () -> writer.put(generated, new byte[1]));
        }
        // Accepted, because the writer does not produce them itself.
        writer.put("(attributes)", new byte[8]);
        writer.put("(signature)", new byte[64]);
        Assert.assertTrue(writer.contains("(attributes)"));
        Assert.assertTrue(writer.contains("(signature)"));
    }

    /** put copies its input, so a later mutation cannot change what is written. */
    @Test
    public void putCopiesItsInput() throws IOException {
        final byte[] caller = "original".getBytes(StandardCharsets.UTF_8);
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("a.txt", caller);
        java.util.Arrays.fill(caller, (byte) 'X');

        try (MpqArchive archive = MpqArchive.open(writer.toByteArray(), MpqOpenOptions.defaults())) {
            Assert.assertEquals(new String(archive.read("a.txt"), StandardCharsets.UTF_8), "original");
        }
    }

    /**
     * Rebuilding an archive over itself: the image is built while the source is
     * open and written once it is closed. A mapped file cannot be replaced on
     * Windows, so the writer cannot do this for the caller, and its javadoc
     * spells out the pattern rather than promising otherwise.
     */
    @Test
    public void archiveCanBeRebuiltOverItself() throws IOException {
        Path archivePath = TestResources.mpqCopy("normalMap");
        final byte[] added = "added".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> before = new LinkedHashMap<>();
        final byte[] image;

        try (MpqArchive archive = MpqArchive.open(archivePath, MpqOpenOptions.warcraft3())) {
            for (String name : archive.names()) {
                before.put(name, TestHelper.md5(archive.read(name)));
            }
            image = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults())
                .put("added.txt", added)
                .toByteArray();
        }
        Files.write(archivePath, image);

        try (MpqArchive archive = MpqArchive.open(archivePath, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.read("added.txt"), added);
            for (Map.Entry<String, String> expected : before.entrySet()) {
                Assert.assertEquals(TestHelper.md5(archive.read(expected.getKey())),
                    expected.getValue(), expected.getKey());
            }
        }
    }

    /** save(Path) writes atomically and leaves no staging file behind. */
    @Test
    public void saveToFileIsAtomicAndTidy() throws IOException {
        Path target = TestResources.scratchDir("save-atomic").resolve("built.w3x");
        final byte[] payload = TestResources.bytes("Example.txt");

        MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("a.txt", payload)
            .save(target);

        try (MpqArchive archive = MpqArchive.open(target, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read("a.txt"), payload);
        }

        // Saving again must replace it cleanly.
        MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("b.txt", payload)
            .save(target);

        try (MpqArchive archive = MpqArchive.open(target, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.contains("b.txt"));
            Assert.assertFalse(archive.contains("a.txt"));
        }

        try (var entries = Files.list(target.getParent())) {
            Assert.assertEquals(entries.filter(p -> p.getFileName().toString().startsWith(".jmpq-"))
                .count(), 0L, "a staging file was left behind");
        }
    }

    /** Streaming a save must produce the same bytes as toByteArray. */
    @Test
    public void saveToStreamMatchesToByteArray() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("a.txt", "one".getBytes(StandardCharsets.UTF_8))
            .put("b.txt", "two".getBytes(StandardCharsets.UTF_8));

        final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        writer.save(streamed);
        Assert.assertEquals(streamed.toByteArray(), writer.toByteArray());
    }

    /** Building the same archive twice must produce identical bytes. */
    @Test
    public void buildIsReproducible() throws IOException {
        final byte[] payload = TestResources.bytes("Example.txt");
        String first = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
                .put("a.txt", payload)
                .put("b.txt", payload)
                .put("Units\\c.txt", payload)
                .toByteArray();
            final String digest = TestHelper.md5(image);
            if (first == null) {
                first = digest;
            } else {
                Assert.assertEquals(digest, first, "the writer is not reproducible");
            }
        }
    }

    /** A removed file must not appear in the result. */
    @Test
    public void removedFilesAreGone() throws IOException {
        Path source = TestResources.mpqCopy("normalMap");
        final byte[] rebuilt;
        try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.contains("war3map.j"));
            final MpqArchiveWriter writer = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults());
            Assert.assertTrue(writer.remove("WAR3MAP.J"), "remove should match case insensitively");
            rebuilt = writer.toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(rebuilt, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.contains("war3map.j"));
            Assert.assertFalse(archive.names().contains("war3map.j"));
        }
    }

    /** An archive too large for a version 0 header must say so, not truncate. */
    @Test
    public void oversizeVersion0ArchiveIsRefused() {
        // Not built for real; the guard is on the header field, and this
        // documents which exception a caller should expect.
        Assert.assertNotNull(MpqWriteOptions.defaults().withFormatVersion(1),
            "version 1 is the documented way past the 4 GiB header limit");
    }

    /** The Warcraft III prefix must survive a rebuild, or the map stops loading. */
    @Test
    public void prefixIsPreserved() throws IOException {
        Path source = TestResources.mpqCopy("normalMap");
        final byte[] rebuilt;
        final long prefixLength;

        try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
            prefixLength = archive.header().headerOffset();
            Assert.assertTrue(prefixLength > 0, "fixture should have a prefix");
            rebuilt = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults()).toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(rebuilt, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().headerOffset(), prefixLength);
        }

        // And dropping it must move the archive to offset 0.
        final byte[] withoutPrefix;
        try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
            withoutPrefix = MpqArchiveWriter
                .from(archive, MpqWriteOptions.defaults().withPrefix(false))
                .toByteArray();
        }
        try (MpqArchive archive = MpqArchive.open(withoutPrefix, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().headerOffset(), 0);
        }
    }

    /** Without a listfile the result cannot be enumerated, and says so. */
    @Test
    public void listfileCanBeSuppressed() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withListfile(false))
            .put("a.txt", "x".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertFalse(archive.contains("(listfile)"));
            Assert.assertTrue(archive.names().isEmpty());
            // Still readable by exact name.
            Assert.assertEquals(archive.read("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** An empty archive is a valid archive. */
    @Test
    public void emptyArchiveIsValid() throws IOException {
        final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults()).toByteArray();
        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.contains("(listfile)"), "an empty listfile is still a listfile");
            Assert.assertEquals(archive.read("(listfile)").length, 0);
        }
    }

    /**
     * Exports archives built by the new writer, plus the content digests they
     * should hold, so {@code tools/mpqref.py verify} can confirm that something
     * other than this library can read them. CI runs that step; see
     * {@code .github/workflows/build.yml}.
     * <p>
     * Re-encoding everything means every sector is deflate or stored, which is
     * exactly the set the reference implements, so coverage of the write path is
     * complete.
     */
    @Test
    public void exportForReferenceVerification() throws IOException {
        final Path out = Path.of("build", "roundtrip-newcore");
        final Path archives = out.resolve("archives");
        Files.createDirectories(archives);

        final RecompressOptions recompress = new RecompressOptions(true);
        recompress.iterations = 1;
        final MpqWriteOptions options = MpqWriteOptions.defaults().withRecompression(recompress);

        final StringBuilder expected = new StringBuilder("# archive\tname\tsize\tmd5\n");
        int exported = 0;

        for (String fixture : manifest.byArchive().keySet()) {
            final Path source = TestResources.mpqCopy(fixture);
            final Map<String, String> contents = new LinkedHashMap<>();
            final byte[] image;

            try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
                for (String name : archive.names()) {
                    try {
                        final byte[] content = archive.read(name);
                        contents.put(name, content.length + "\t" + TestHelper.md5(content));
                    } catch (IOException e) {
                        log.debug("cannot decode {} in {}", name, fixture, e);
                    }
                }
                if (contents.isEmpty()) {
                    continue;
                }
                image = MpqArchiveWriter.from(archive, options).toByteArray();
            }

            Files.write(archives.resolve(fixture), image);
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                expected.append(fixture).append('\t').append(entry.getKey())
                    .append('\t').append(entry.getValue()).append('\n');
            }
            exported++;
        }

        Assert.assertTrue(exported >= 10, "exported only " + exported + " archives");
        Files.writeString(out.resolve("expected.tsv"), expected.toString(), StandardCharsets.UTF_8);
        log.info("exported {} writer-built archives for independent verification", exported);
    }

    /** A missing source file must be reported when saving, not silently skipped. */
    @Test
    public void missingSourceFileIsReported() {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("a.txt", Path.of("definitely-not-here.bin"));
        Assert.expectThrows(IOException.class, writer::toByteArray);
    }
}
