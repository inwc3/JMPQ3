package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.AttributesFile;
import systems.crigges.jmpq3.BlockTable;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The deprecated API that survives for compatibility.
 * <p>
 * Reducing {@code JMpqEditor} to a facade stopped the facade itself from using
 * most of this code, which showed up as a coverage cliff. That is a real gap
 * rather than a metric artefact: these types are still public, still supported
 * and still reachable, and until this class existed nothing exercised them
 * directly - the old tests only reached them incidentally through the editor's
 * internals.
 */
public class LegacyApiTests {

    // -------------------------------------------------------------- MpqFile

    /**
     * {@link JMpqEditor#getMpqFile(String)} hands out a legacy {@code MpqFile},
     * which decodes independently of the core. It must agree with the core on
     * every fixture, or the deprecated path is quietly returning different
     * bytes from the supported one.
     */
    @Test
    public void legacyMpqFileAgreesWithTheFacade() throws IOException {
        int compared = 0;
        for (Path mpq : TestResources.mpqCopies()) {
            try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                for (String name : editor.getFileNames()) {
                    final byte[] viaFacade;
                    try {
                        viaFacade = editor.extractFileAsBytes(name);
                    } catch (IOException undecodable) {
                        continue;
                    }
                    final MpqFile file = editor.getMpqFile(name);
                    Assert.assertEquals(file.extractToBytes(), viaFacade,
                        mpq.getFileName() + " / " + name);
                    Assert.assertEquals(file.getNormalSize(), viaFacade.length, name);
                    Assert.assertEquals(file.getName(), name);
                    compared++;
                }
            }
        }
        Assert.assertTrue(compared > 100, "only compared " + compared + " files");
    }

    /** The legacy streaming and file-writing paths must match the byte array one. */
    @Test
    public void legacyMpqFileOutputPathsAgree() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        Path out = TestResources.scratchDir("legacy-extract");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            for (String name : editor.getFileNames()) {
                final MpqFile file = editor.getMpqFile(name);
                final byte[] bytes = file.extractToBytes();

                final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
                editor.getMpqFile(name).extractToOutputStream(streamed);
                Assert.assertEquals(streamed.toByteArray(), bytes, name);

                final Path target = out.resolve(name.replace('\\', '_'));
                editor.getMpqFile(name).extractToPath(target);
                Assert.assertEquals(java.nio.file.Files.readAllBytes(target), bytes, name);

                Assert.assertNotNull(file.toString());
            }
        }
    }

    /** Extraction must never close a stream it was handed. */
    @Test
    public void legacyMpqFileDoesNotCloseCallerStreams() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            final Tracking sink = new Tracking();
            for (String name : editor.getFileNames()) {
                editor.getMpqFile(name).extractToOutputStream(sink);
                Assert.assertFalse(sink.closed, "closed the caller's stream on " + name);
            }
        }
    }

    private static final class Tracking extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** Sector counting is exposed and must stay overflow free. */
    @Test
    public void sectorCountIsExposedAndSafe() {
        Assert.assertEquals(MpqFile.sectorCount(0, 4096), 0);
        Assert.assertEquals(MpqFile.sectorCount(4097, 4096), 2);
        Assert.assertEquals(MpqFile.sectorCount(Integer.MAX_VALUE, 4096), 524288);
        Assert.expectThrows(IllegalArgumentException.class, () -> MpqFile.sectorCount(-1, 4096));
        Assert.expectThrows(IllegalArgumentException.class, () -> MpqFile.sectorCount(1, 0));
    }

    // ----------------------------------------------------------- BlockTable

    /** The deprecated index accessors must describe the same archive. */
    @Test
    public void deprecatedIndexAccessorsDescribeTheArchive() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            final BlockTable blocks = editor.getBlockTable();
            Assert.assertNotNull(blocks);
            Assert.assertTrue(blocks.size() > 0);
            Assert.assertEquals(blocks.getAllValidBlocks().size(), editor.getTotalFileCount());

            for (BlockTable.Block block : blocks.getAllValidBlocks()) {
                Assert.assertTrue(block.hasFlag(MpqFile.EXISTS));
                Assert.assertTrue(block.getFilePosition() >= 0);
                Assert.assertNotNull(block.printFlags());
                Assert.assertNotNull(block.toString());
            }

            // Out of range must be reported, not silently produce garbage.
            Assert.expectThrows(IOException.class, () -> blocks.getBlockAtPos(-1));
            Assert.expectThrows(IOException.class, () -> blocks.getBlockAtPos(blocks.size()));

            Assert.assertNotNull(editor.getHashTable());
            Assert.assertTrue(editor.getHashTable().hasFile("war3map.j"));
            Assert.assertTrue(editor.getHashTable().capacity() > 0);
            Assert.assertTrue(editor.getHashTable().size() > 0);
        }
    }

    /** Every readable block must be reachable without a name. */
    @Test
    public void blocksAreReachableWithoutNames() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            final List<MpqFile> files = editor.getMpqFilesByBlockTable();
            Assert.assertFalse(files.isEmpty());
            for (MpqFile file : files) {
                Assert.assertTrue(file.getCompressedSize() >= 0);
            }
        }
    }

    // ------------------------------------------------------- AttributesFile

    /**
     * {@code (attributes)} round-trips through the legacy parser.
     * <p>
     * P2-4 has since replaced the fixed layout with one derived from the
     * bytemask the file declares, so the entry count no longer loses one. The
     * deprecated class keeps working; {@code MpqAttributesTests} covers what it
     * now does, and {@link org.inwc3.jmpq.MpqAttributes} covers the format
     * properly.
     */
    @Test
    public void attributesFileRoundTrips() {
        final int entries = 4;
        final AttributesFile written = new AttributesFile(entries);
        for (int i = 0; i < entries; i++) {
            written.setEntry(i, 0x1000 + i, 0x2000L + i);
        }

        final byte[] image = written.buildFile();
        Assert.assertEquals(image.length, 8 + 12 * entries);
        Assert.assertEquals(image[0], 100, "format version");
        Assert.assertEquals(image[4], 3, "crc plus timestamp bytemask");

        final AttributesFile read = new AttributesFile(image);
        Assert.assertEquals(read.entries(), entries);
        for (int i = 0; i < read.entries(); i++) {
            Assert.assertEquals(read.getCrc32()[i], 0x1000 + i);
        }
    }

    /**
     * The {@code buildAttributes} flag is honoured now, and off by default.
     * <p>
     * It used to be accepted and dropped with a warning. Turning it on by
     * default once generation worked would have been a silent performance cliff:
     * the checksums are taken over decoded content, so it forces a decode of
     * every file precisely where the verbatim copy path would otherwise avoid
     * one. So the plain {@code close()} keeps producing what it always produced,
     * and asking explicitly now works.
     */
    @Test
    public void legacyCloseHonoursTheAttributesFlag() throws IOException {
        final Path without = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(without, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("a.txt", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (org.inwc3.jmpq.MpqArchive archive = org.inwc3.jmpq.MpqArchive.open(without,
            org.inwc3.jmpq.MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.attributes().isEmpty(),
                "the default close has never produced attributes");
        }

        final Path with = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(with, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("a.txt", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            editor.close(true, true, false);
        }
        try (org.inwc3.jmpq.MpqArchive archive = org.inwc3.jmpq.MpqArchive.open(with,
            org.inwc3.jmpq.MpqOpenOptions.warcraft3())) {
            final var attributes = archive.attributes().orElseThrow(
                () -> new AssertionError("asked for attributes and did not get them"));
            Assert.assertEquals(attributes.entries(), archive.header().blockTableEntries());
            Assert.assertEquals(archive.read("a.txt"),
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * A recovered {@code (attributes)} must not collide with a generated one.
     * <p>
     * An archive that cannot enumerate itself can be given an external list
     * file, and if that list names the archive's existing {@code (attributes)}
     * the rebuild recovers it. Asking to generate attributes in the same breath
     * then put two entries under one name, which the writer refuses -- so the
     * close failed instead of rebuilding, which is worse than either outcome the
     * caller asked for.
     */
    @Test
    public void recoveredAttributesDoNotCollideWithGeneratedOnes() throws IOException {
        final byte[] attributes = org.inwc3.jmpq.MpqAttributes.build(new int[2], new long[2]);

        // An archive with no list file, so nothing is enumerable, holding an
        // (attributes) that only an external list can name.
        final Path map = TestResources.scratchDir("recovered-attrs").resolve("map.w3x");
        org.inwc3.jmpq.MpqArchiveWriter
            .create(org.inwc3.jmpq.MpqWriteOptions.defaults().withListfile(false))
            .put(org.inwc3.jmpq.MpqAttributes.NAME, attributes)
            .put("war3map.j", "script".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .save(map);

        final Path listfile = TestResources.scratchDir("recovered-attrs-list").resolve("list.txt");
        java.nio.file.Files.writeString(listfile,
            org.inwc3.jmpq.MpqAttributes.NAME + "\r\nwar3map.j\r\n");

        try (JMpqEditor editor = new JMpqEditor(map, MPQOpenOption.FORCE_V0)) {
            editor.setExternalListfile(listfile.toFile());
            editor.insertByteArray("added.txt",
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Generation requested while the recovered one is also in play.
            editor.close(true, true, false);
        }

        try (org.inwc3.jmpq.MpqArchive rebuilt = org.inwc3.jmpq.MpqArchive.open(map,
            org.inwc3.jmpq.MpqOpenOptions.warcraft3())) {
            final var generated = rebuilt.attributes().orElseThrow(
                () -> new AssertionError("attributes were requested and not produced"));
            Assert.assertEquals(generated.entries(), rebuilt.header().blockTableEntries(),
                "the generated attributes should describe this archive, not the old one");
            Assert.assertEquals(rebuilt.read("war3map.j"),
                "script".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Assert.assertEquals(rebuilt.read("added.txt"),
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** The CRC32 helper must match java.util.zip for the same bytes. */
    @Test
    public void attributesCrcMatchesTheJdk() {
        final byte[] payload = TestResources.bytes("Example.txt");
        final java.util.zip.CRC32 expected = new java.util.zip.CRC32();
        expected.update(payload);

        final AttributesFile attributes = new AttributesFile(1);
        Assert.assertEquals(attributes.getCrc32(payload), (int) expected.getValue());
        Assert.assertEquals(attributes.getCrc32(new byte[0]), 0);
    }

    /** Timestamps and names are addressable. */
    @Test
    public void attributesTimestampsAndNamesAreAddressable() {
        final AttributesFile attributes = new AttributesFile(3);
        attributes.setEntry(0, 1, 111L);
        attributes.setEntry(1, 2, 222L);
        attributes.setEntry(2, 3, 333L);

        Assert.assertEquals(attributes.getTimestamps()[1], 222L);
        Assert.assertEquals(attributes.entries(), 3);
        Assert.assertNotNull(attributes.getFile());

        attributes.setNames(new java.util.ArrayList<>(List.of("a.txt", "b.txt", "c.txt")));
        Assert.assertEquals(attributes.getEntry("b.txt"), 1);
        Assert.assertEquals(attributes.getEntry("absent.txt"), -1);
    }
}
