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
 * directly — the old tests only reached them incidentally through the editor's
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
