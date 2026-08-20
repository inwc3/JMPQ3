package systems.crigges.jmpq3test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.BlockTable;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqFile;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Behaviour tests for the (deprecated) {@link JMpqEditor} facade.
 * <p>
 * Every archive these tests touch is a private copy handed out by
 * {@link TestResources}, so tests never mutate {@code build/resources} and can
 * run in any order.
 */
public class MpqTests {
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private static List<Path> getMpqs() {
        return TestResources.mpqCopies();
    }

    private static Path getFile(String name) {
        return TestResources.file(name);
    }

    @Test
    public void createEmptyArchiveCanBeOpenedAndRebuilt() throws IOException {
        Path archive = TestResources.scratchDir("empty-archive").resolve("jmpq-empty.w3x");

        JMpqEditor.createEmptyArchive(archive.toFile());

        try (JMpqEditor mpqEditor = new JMpqEditor(archive.toFile(), MPQOpenOption.FORCE_V0)) {
            mpqEditor.insertByteArray("war3map.j", "test script".getBytes(StandardCharsets.UTF_8));
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(archive.toFile(), MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.hasFile("war3map.j"));
            Assert.assertEquals(new String(mpqEditor.extractFileAsBytes("war3map.j"), StandardCharsets.UTF_8), "test script");
        }
    }

    @Test
    public void cryptoTest() {
        byte[] bytes = "Hello World!".getBytes(StandardCharsets.UTF_8);

        final ByteBuffer workBuffer = ByteBuffer.allocate(bytes.length);
        final MPQEncryption encryptor = new MPQEncryption(-1011927184, false);
        encryptor.processFinal(ByteBuffer.wrap(bytes), workBuffer);
        workBuffer.flip();
        encryptor.changeKey(-1011927184, true);
        encryptor.processSingle(workBuffer);
        workBuffer.flip();

        Assert.assertEquals(workBuffer.array(), bytes);
    }

    @Test
    public void hashTableTest() throws IOException {
        // get real example file paths
        final String fp1;
        final String fp2;
        try (InputStream listFileFile = getClass().getClassLoader().getResourceAsStream("DefaultListfile.txt");
             Scanner listFile = new Scanner(listFileFile, StandardCharsets.UTF_8)) {
            fp1 = listFile.nextLine();
            fp2 = listFile.nextLine();
        }

        // small test hash table
        final HashTable ht = new HashTable(8);
        final short defaultLocale = HashTable.DEFAULT_LOCALE;
        final short germanLocale = 0x407;
        final short frenchLocale = 0x40c;
        final short russianLocale = 0x419;

        // assignment test
        ht.setFileBlockIndex(fp1, defaultLocale, 0);
        ht.setFileBlockIndex(fp2, defaultLocale, 1);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertEquals(ht.getFileBlockIndex(fp2, defaultLocale), 1);

        // deletion test
        ht.removeFile(fp2, defaultLocale);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertFalse(ht.hasFile(fp2));

        // locale test
        ht.setFileBlockIndex(fp1, germanLocale, 2);
        ht.setFileBlockIndex(fp1, frenchLocale, 3);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, germanLocale), 2);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, frenchLocale), 3);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, russianLocale), 0);

        // file path deletion test
        ht.setFileBlockIndex(fp2, defaultLocale, 1);
        ht.removeFileAll(fp1);
        Assert.assertFalse(ht.hasFile(fp1));
        Assert.assertEquals(ht.getFileBlockIndex(fp2, defaultLocale), 1);
    }

    @Test
    public void testException() {
        Assert.expectThrows(JMpqException.class, () -> new BlockTable(ByteBuffer.wrap(new byte[0])).getBlockAtPos(-1));
    }

    @Test
    public void testInsertAndExtract() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            mpqEditor.insertFile("test.txt", getFile("Example.txt").toFile());
        }

        // Test if mpq is still valid
        try (JMpqEditor mpqEditor2 = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            byte[] bytes = mpqEditor2.extractFileAsBytes("test.txt");
            Assert.assertEquals(bytes, TestResources.bytes("Example.txt"));
        }
    }

    @Test
    public void testRebuild() throws IOException {
        for (Path mpq : getMpqs()) {
            log.info("rebuild: {}", mpq.getFileName());
            List<String> namesBefore;
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                namesBefore = new ArrayList<>(mpqEditor.getFileNames());
                mpqEditor.close(false, false, false);
            }

            // The rebuilt archive must still be a readable archive holding the
            // same file names -- the old test asserted nothing at all.
            try (JMpqEditor rebuilt = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                for (String name : namesBefore) {
                    if (name.equals("(listfile)")) {
                        // buildListfile == false, so this one is expected to be gone.
                        continue;
                    }
                    Assert.assertTrue(rebuilt.hasFile(name),
                        mpq.getFileName() + " lost " + name + " during rebuild");
                }
            }
        }
    }

    @Test
    public void testInsertOrder() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            mpqEditor.insertByteArray("a", new byte[12]);
            mpqEditor.insertByteArray("b", new byte[12]);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            int aI = mpqEditor.getHashTable().getBlockIndexOfFile("a");
            int bI = mpqEditor.getHashTable().getBlockIndexOfFile("b");
            Assert.assertTrue(bI > aI);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            mpqEditor.insertByteArray("d", new byte[12]);
            mpqEditor.insertByteArray("c", new byte[12]);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            int dI = mpqEditor.getHashTable().getBlockIndexOfFile("d");
            int cI = mpqEditor.getHashTable().getBlockIndexOfFile("c");
            Assert.assertTrue(cI > dI);
        }
    }

    @Test
    public void testExternalListfile() throws Exception {
        Path mpq = TestResources.mpqCopy("normalMap");
        Path listFile = getFile("listfile.txt");
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (mpqEditor.isCanWrite()) {
                mpqEditor.deleteFile("(listfile)");
            }
            mpqEditor.setExternalListfile(listFile.toFile());
            Assert.assertTrue(mpqEditor.getListfileEntries().contains("war3map.w3a"));
        }
    }

    @Test
    public void testRecompressBuild() throws IOException {
        RecompressOptions options = new RecompressOptions(true);
        options.newSectorSizeShift = 15;
        for (Path mpq : getMpqs()) {
            log.info("recompress: {}", mpq.getFileName());
            options.useZopfli = !options.useZopfli;

            List<String> namesBefore;
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                namesBefore = new ArrayList<>(mpqEditor.getFileNames());
                mpqEditor.close(true, true, options);
            }

            // Recompression must be content preserving.
            try (JMpqEditor rebuilt = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                for (String name : namesBefore) {
                    Assert.assertTrue(rebuilt.hasFile(name),
                        mpq.getFileName() + " lost " + name + " during recompression");
                }
            }
        }
    }

    @Test
    public void testExtractAll() throws IOException {
        for (Path mpq : getMpqs()) {
            Path out = TestResources.scratchDir("extract-all");
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                mpqEditor.extractAllFiles(out.toFile());
            }
            try (Stream<Path> extracted = Files.walk(out)) {
                Assert.assertTrue(extracted.anyMatch(Files::isRegularFile),
                    mpq.getFileName() + " extracted nothing at all");
            }
        }
    }

    @Test
    public void testExtractScriptFile() throws IOException {
        String expected = normaliseNewlines(new String(TestResources.bytes("war3map.j"), StandardCharsets.UTF_8));
        for (Path mpq : getMpqs()) {
            log.info("test extract script: {}", mpq.getFileName());
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                if (mpqEditor.hasFile("war3map.j")) {
                    Assert.assertEquals(normaliseNewlines(mpqEditor.extractFileAsString("war3map.j")), expected);
                }
            }
        }
    }

    @Test
    public void testExtractScriptFileBA() throws IOException {
        String expected = normaliseNewlines(new String(TestResources.bytes("war3map.j"), StandardCharsets.UTF_8));
        for (Path mpq : getMpqs()) {
            log.info("test extract script from byte array: {}", mpq.getFileName());
            try (JMpqEditor mpqEditor = new JMpqEditor(Files.readAllBytes(mpq),
                MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                if (mpqEditor.hasFile("war3map.j")) {
                    Assert.assertEquals(normaliseNewlines(mpqEditor.extractFileAsString("war3map.j")), expected);
                }
            }
        }
    }

    private static String normaliseNewlines(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    @Test
    public void testInsertDeleteRegularFile() throws IOException {
        for (Path mpq : getMpqs()) {
            insertAndDelete(mpq, "Example.txt");
        }
    }

    @Test
    public void testInsertByteArray() throws IOException {
        for (Path mpq : getMpqs()) {
            insertByteArrayAndVerify(mpq, "Example.txt");
        }
    }

    @Test
    public void testInsertDeleteZeroLengthFile() throws IOException {
        for (Path mpq : getMpqs()) {
            insertAndDelete(mpq, "0ByteExample.txt");
        }
    }

    @Test
    public void testMultipleInstances() throws IOException {
        for (Path mpq : getMpqs()) {
            List<JMpqEditor> editors = List.of(
                new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0),
                new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0),
                new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0));
            try {
                for (JMpqEditor editor : editors) {
                    editor.extractAllFiles(TestResources.scratchDir("multi-instance").toFile());
                }
            } finally {
                for (JMpqEditor editor : editors) {
                    editor.close();
                }
            }
        }
    }

    @Test
    public void testIncompressibleFile() throws IOException {
        for (Path mpq : getMpqs()) {
            log.info("incompressible insert: {}", mpq.getFileName());
            insertAndVerify(mpq, "incompressible.w3u");
        }
    }

    @Test
    public void testDuplicatePaths() throws IOException {
        for (Path mpq : getMpqs()) {
            if (mpq.getFileName().toString().equals("invalidHashSize.scx")) {
                continue;
            }
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                if (!mpqEditor.isCanWrite()) {
                    continue;
                }
                mpqEditor.insertByteArray("Test", "bytesasdadasdad".getBytes(StandardCharsets.UTF_8));
                Assert.expectThrows(IllegalArgumentException.class,
                    () -> mpqEditor.insertByteArray("Test", "bytesasdadasdad".getBytes(StandardCharsets.UTF_8)));
                Assert.expectThrows(IllegalArgumentException.class,
                    () -> mpqEditor.insertByteArray("teST", "bytesasdadasdad".getBytes(StandardCharsets.UTF_8)));
                mpqEditor.insertByteArray("teST", "bytesasdadasdad".getBytes(StandardCharsets.UTF_8), true);
            }
        }
    }

    private void insertByteArrayAndVerify(Path mpq, String filename) throws IOException {
        String hashBefore;
        byte[] bytes;

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            hashBefore = TestHelper.md5(mpq);
            bytes = TestResources.bytes(filename);
            mpqEditor.insertByteArray(filename, bytes.clone());
        }

        try (JMpqEditor mpqEditor = verifyMpq(mpq, filename, hashBefore, bytes)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }
    }

    private JMpqEditor verifyMpq(Path mpq, String filename, String hashBefore, byte[] bytes) throws IOException {
        String hashAfter = TestHelper.md5(mpq);
        // If this fails, the mpq is not changed by the insert file command and something went wrong
        Assert.assertNotEquals(hashBefore, hashAfter);

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.hasFile(filename));
            byte[] bytes2 = mpqEditor.extractFileAsBytes(filename);
            Assert.assertEquals(bytes2, bytes);
            mpqEditor.deleteFile(filename);
        }

        return new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
    }

    private void insertAndVerify(Path mpq, String filename) throws IOException {
        String hashBefore;
        byte[] bytes;
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            hashBefore = TestHelper.md5(mpq);
            bytes = TestResources.bytes(filename);
            mpqEditor.insertFile(filename, getFile(filename).toFile());
        }

        try (JMpqEditor mpqEditor = verifyMpq(mpq, filename, hashBefore, bytes)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }
    }

    private void insertAndDelete(Path mpq, String filename) throws IOException {
        Path source = getFile(filename);
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            Assert.assertFalse(mpqEditor.hasFile(filename));
            String hashBefore = TestHelper.md5(mpq);
            mpqEditor.insertFile(filename, source.toFile());
            mpqEditor.deleteFile(filename);
            mpqEditor.insertFile(filename, source.toFile());
            mpqEditor.close();

            String hashAfter = TestHelper.md5(mpq);
            // If this fails, the mpq is not changed by the insert file command and something went wrong
            Assert.assertNotEquals(hashBefore, hashAfter);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.hasFile(filename));

            mpqEditor.deleteFile(filename);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            mpqEditor.insertFile(filename, source.toFile(), true);
            mpqEditor.insertFile(filename, source.toFile(), true);

            mpqEditor.deleteFile(filename);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }
    }

    @Test
    public void testRemoveHeaderoffset() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            mpqEditor.setKeepHeaderOffset(false);
            mpqEditor.close();

            byte[] bytes = new byte[4];
            try (InputStream in = Files.newInputStream(mpq)) {
                Assert.assertEquals(in.read(bytes), 4);
            }
            ByteBuffer order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            Assert.assertEquals(order.getInt(), JMpqEditor.ARCHIVE_HEADER_MAGIC);
        }
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.isCanWrite());
        }
    }

    private Set<Path> getFiles(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).sorted().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    @Test
    public void newBlocksizeBufferOverflow() throws IOException {
        Path mpq = TestResources.file("newBlocksizeBufferOverflow/mpq/newBlocksizeBufferOverflow.w3x");
        Path insertions = TestResources.directory("newBlocksizeBufferOverflow/insertions");

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            for (Path file : getFiles(insertions)) {
                // MPQ paths use backslashes regardless of platform.
                String inName = insertions.relativize(file).toString().replace('/', '\\');
                mpqEditor.insertFile(inName, file.toFile());
            }
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            for (Path file : getFiles(insertions)) {
                String inName = insertions.relativize(file).toString().replace('/', '\\');
                Assert.assertTrue(mpqEditor.hasFile(inName), "missing after rebuild: " + inName);
                Assert.assertEquals(mpqEditor.extractFileAsBytes(inName), Files.readAllBytes(file));
            }
        }
    }

    @Test
    public void testForGetMpqFileByBlock() throws IOException {
        for (Path mpq : getMpqs()) {
            if (mpq.getFileName().toString().equals("invalidHashSize.scx")) {
                continue;
            }
            try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                Assert.assertFalse(mpqEditor.getMpqFilesByBlockTable().isEmpty());
                BlockTable blockTable = mpqEditor.getBlockTable();
                Assert.assertNotNull(blockTable);

                for (BlockTable.Block block : blockTable.getAllValidBlocks()) {
                    if (block.hasFlag(MpqFile.ENCRYPTED)) {
                        continue;
                    }
                    Assert.assertNotNull(mpqEditor.getMpqFileByBlock(block));
                }
            }
        }
    }

    @Test
    public void testUnmodifiedArchivesAreNotRewritten() throws IOException {
        for (Path mpq : getMpqs()) {
            String before = TestHelper.md5(mpq);
            try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                Assert.assertNotNull(editor.getFileNames());
            }
            Assert.assertEquals(TestHelper.md5(mpq), before,
                "read-only open modified " + mpq.getFileName());
        }
    }

    /** Sanity check on the fixture set itself, so a lost fixture is loud. */
    @Test
    public void fixturesArePresent() {
        List<Path> mpqs = getMpqs();
        Assert.assertTrue(mpqs.size() >= 12, "expected the full archive fixture set, got " + mpqs);
        List<String> names = mpqs.stream().map(p -> p.getFileName().toString()).sorted().toList();
        Assert.assertTrue(names.contains("normalMap.w3x"), Arrays.toString(names.toArray()));
    }
}
