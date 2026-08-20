package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.MpqFile;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.JzLibHelper;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.channels.NonWritableChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * One test per Phase 0 correctness fix, named after the audit item it pins.
 * <p>
 * These are deliberately narrow: each one fails against the pre-2.0 behaviour
 * and passes after the corresponding fix, so a regression is attributable.
 */
public class Phase0RegressionTests {

    // ---------------------------------------------------------------- P0-1

    /**
     * P0-1: the library must hold no global mutable state. The old
     * {@code public static File tempDir} was shared between every editor in the
     * JVM, and every open wiped the directory it pointed at.
     */
    @Test
    public void p0_1_noPublicMutableStaticState() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String className : libraryClasses()) {
            for (Field field : Class.forName(className).getDeclaredFields()) {
                final int mods = field.getModifiers();
                if (Modifier.isStatic(mods) && !Modifier.isFinal(mods) && !field.isSynthetic()) {
                    offenders.add(className + "." + field.getName());
                }
            }
        }
        Assert.assertEquals(offenders, List.of(), "mutable static state found");
    }

    /**
     * P0-1: rebuilding must not stage anything on the file system. Two
     * concurrent rebuilds used to race on one shared temp directory.
     */
    @Test
    public void p0_1_rebuildCreatesNoTemporaryFiles() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        Path jmpqTempDir = Path.of(System.getProperty("java.io.tmpdir"), "jmpq");
        boolean existedBefore = Files.exists(jmpqTempDir);

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("temp-probe.txt", "content".getBytes(StandardCharsets.UTF_8));
        }

        if (!existedBefore) {
            Assert.assertFalse(Files.exists(jmpqTempDir),
                "rebuild created the legacy shared temp directory " + jmpqTempDir);
        }
    }

    // ---------------------------------------------------------------- P0-2

    /**
     * P0-2: the compression codecs were static singletons, so two archives
     * processed at once corrupted each other's data. Rebuild several archives
     * concurrently and require byte-identical results to a sequential run.
     */
    @Test
    public void p0_2_concurrentRebuildsMatchSequentialRebuilds() throws Exception {
        final int copies = 6;
        final byte[] payload = TestResources.bytes("war3map.j");

        List<String> sequential = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            sequential.add(rebuildAndDigest(TestResources.mpqCopy("normalMap"), payload));
        }

        final List<Path> parallelInputs =
            IntStream.range(0, copies).mapToObj(i -> TestResources.mpqCopy("normalMap")).toList();
        final List<String> parallel;
        try (ExecutorService pool = Executors.newFixedThreadPool(copies)) {
            final List<Callable<String>> jobs = parallelInputs.stream()
                .map(p -> (Callable<String>) () -> rebuildAndDigest(p, payload))
                .toList();
            final List<Future<String>> futures = pool.invokeAll(jobs);
            parallel = new ArrayList<>();
            for (Future<String> future : futures) {
                parallel.add(future.get());
            }
        }

        Assert.assertEquals(parallel, sequential, "concurrent rebuilds diverged from sequential ones");
        Assert.assertEquals(sequential.stream().distinct().count(), 1L,
            "rebuilding the same input twice produced different archives");
    }

    private static String rebuildAndDigest(Path mpq, byte[] payload) throws IOException {
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("concurrent.j", payload, true);
        }
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertEquals(editor.extractFileAsBytes("concurrent.j"), payload);
        }
        return TestHelper.md5(mpq);
    }

    /**
     * P0-2: concurrent decompression of the same archive content must agree
     * with the single-threaded result.
     */
    @Test
    public void p0_2_concurrentExtractionIsConsistent() throws Exception {
        final Path mpq = TestResources.mpqCopy("wavTest");
        final List<String> expected;
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            expected = digestAll(editor);
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            final List<Callable<List<String>>> jobs = IntStream.range(0, 8)
                .mapToObj(i -> (Callable<List<String>>) () -> {
                    try (JMpqEditor editor =
                             new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                        return digestAll(editor);
                    }
                }).toList();
            for (Future<List<String>> future : pool.invokeAll(jobs)) {
                Assert.assertEquals(future.get(), expected);
            }
        }
    }

    private static List<String> digestAll(JMpqEditor editor) throws IOException {
        List<String> digests = new ArrayList<>();
        for (String name : editor.getFileNames()) {
            digests.add(name + "=" + TestHelper.md5(editor.extractFileAsBytes(name)));
        }
        return digests;
    }

    // ---------------------------------------------------------------- P0-3

    /**
     * P0-3: {@code FLAG_LMZA = 0x12} overlaps {@code DEFLATE | BZIP2}, and the
     * old test {@code (type & 0x12) != 0} therefore fired for plain deflate.
     * A plain deflate sector must decode, not be rejected as LZMA.
     */
    @Test
    public void p0_3_plainDeflateSectorIsNotMistakenForLzma() throws IOException {
        final byte[] raw = "deflate me, and do not call me LZMA".repeat(8)
            .getBytes(StandardCharsets.UTF_8);
        final byte[] sector = withTypeByte(0x02, JzLibHelper.deflate(raw, true));

        Assert.assertEquals(CompressionUtil.decompress(sector, sector.length, raw.length), raw);
    }

    /**
     * P0-3: for format version 0 and 1, {@code 0x12} is {@code BZIP2 | ZLIB},
     * exactly as StormLib's {@code SCompDecompressInternal} reads it. For
     * version 2 and above the very same byte means standalone LZMA.
     */
    @Test
    public void p0_3_maskDispatchAppliesStagesInStormlibOrder() throws IOException {
        // Zero-heavy input so the sparse stage genuinely shrinks it and the
        // intermediate stays below the final size, as StormLib's fixed-size
        // staging buffers require.
        final byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i += 512) {
            raw[i] = (byte) (i / 512 + 1);
        }

        // Encoded the way StormLib decodes 0x22: sparse first, then zlib on top.
        final byte[] sparse = sparseEncode(raw);
        final byte[] sector = withTypeByte(0x22, JzLibHelper.deflate(sparse, true));

        Assert.assertEquals(CompressionUtil.decompress(sector, sector.length, raw.length), raw,
            "SPARSE|ZLIB must decode zlib first and sparse second");
    }

    /**
     * P0-3/P2-6: sparse alone must decode, instead of throwing "Unsupported
     * compression sparse".
     */
    @Test
    public void p0_3_sparseSectorDecodes() throws IOException {
        final byte[] raw = new byte[1000];
        Arrays.fill(raw, 300, 320, (byte) 0x5A);
        final byte[] sector = withTypeByte(0x20, sparseEncode(raw));

        Assert.assertEquals(CompressionUtil.decompress(sector, sector.length, raw.length), raw);
    }

    /**
     * P0-3: an unknown mask bit must produce a diagnostic, not a wrong answer.
     */
    @Test
    public void p0_3_unknownCompressionMaskIsReported() {
        // 0x04 is the one documented-but-unassigned bit.
        final byte[] sector = withTypeByte(0x04, new byte[]{1, 2, 3, 4});
        JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> CompressionUtil.decompress(sector, sector.length, 64));
        Assert.assertTrue(thrown.getMessage().contains("0x4"), thrown.getMessage());
    }

    private static byte[] withTypeByte(int type, byte[] payload) {
        final byte[] sector = new byte[payload.length + 1];
        sector[0] = (byte) type;
        System.arraycopy(payload, 0, sector, 1, payload.length);
        return sector;
    }

    /**
     * Minimal StormLib-compatible sparse encoder, for test input only: a
     * big-endian length, then literal runs with the high bit set and zero runs
     * without it.
     */
    private static byte[] sparseEncode(byte[] raw) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(raw.length >>> 24);
        out.write(raw.length >>> 16);
        out.write(raw.length >>> 8);
        out.write(raw.length);

        int i = 0;
        while (i < raw.length) {
            int zeros = 0;
            while (i + zeros < raw.length && raw[i + zeros] == 0 && zeros < 0x7F + 3) {
                zeros++;
            }
            if (zeros >= 3) {
                out.write(zeros - 3);
                i += zeros;
                continue;
            }
            int literals = 0;
            while (i + literals < raw.length && literals < 0x7F + 1) {
                // Stop a literal run once a zero run long enough to pay for
                // itself begins.
                int lookahead = 0;
                while (i + literals + lookahead < raw.length && raw[i + literals + lookahead] == 0) {
                    lookahead++;
                }
                if (lookahead >= 3) {
                    break;
                }
                literals++;
            }
            out.write(0x80 | (literals - 1));
            out.write(raw, i, literals);
            i += literals;
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- P0-4

    /**
     * P0-4: pending inserts were held in an identity-keyed map, so deleting by
     * an equal-but-distinct String silently failed and the file reappeared on
     * close.
     */
    @Test
    public void p0_4_deleteWorksWithAnEqualButDistinctStringInstance() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray(new String("ghost.txt"), "boo".getBytes(StandardCharsets.UTF_8));
            editor.deleteFile(new String("ghost.txt"));
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.hasFile("ghost.txt"), "deleted file came back on rebuild");
        }
    }

    // ---------------------------------------------------------------- P0-5

    /**
     * P0-5: MPQ paths are case insensitive and backslash separated, so the
     * pending-insert map and the list file must agree about identity. They used
     * to disagree: one compared raw strings, the other MPQ hashes.
     */
    @Test
    public void p0_5_nameNormalisationIsConsistentAcrossStructures() throws IOException {
        // Case folds...
        Assert.assertEquals(MpqNames.canonical("Units\\Test.txt"), MpqNames.canonical("units\\TEST.TXT"));
        Assert.assertEquals(MpqNames.fileKey("Units\\Test.txt"), MpqNames.fileKey("UNITS\\test.txt"));

        // ...separators do not. '/' is an ordinary character to the MPQ hash,
        // so these name two different files, exactly as StormLib treats them.
        Assert.assertNotEquals(MpqNames.canonical("Units/Test.txt"), MpqNames.canonical("Units\\Test.txt"));
        Assert.assertNotEquals(MpqNames.fileKey("Units/Test.txt"), MpqNames.fileKey("Units\\Test.txt"));

        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("Units\\Case.txt", "a".getBytes(StandardCharsets.UTF_8));
            // Same file by MPQ rules, so a non-overriding insert must be refused
            // and a delete must find it.
            Assert.expectThrows(IllegalArgumentException.class,
                () -> editor.insertByteArray("UNITS\\CASE.TXT", "b".getBytes(StandardCharsets.UTF_8)));
            editor.deleteFile("UNITS\\case.txt");
            Assert.assertFalse(editor.getListfileEntries().contains("Units\\Case.txt"));
        }
    }

    /**
     * P0-5: case folding must not depend on the default locale. Under a Turkish
     * locale {@code String.toUpperCase()} maps {@code i} to {@code İ}, which
     * hashes differently from what every other MPQ implementation produces.
     */
    @Test
    public void p0_5_caseFoldingIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale.Builder().setLanguage("tr").setRegion("TR").build());
            Assert.assertEquals(MpqNames.canonical("war3map.imp"), "WAR3MAP.IMP");
            Assert.assertEquals(MpqNames.fileKey("war3map.imp"), turkishFreeKey());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private static long turkishFreeKey() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.ROOT);
            return MpqNames.fileKey("war3map.imp");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    // ---------------------------------------------------------------- P0-6

    /**
     * P0-6: the list file was a {@code HashMap} keyed on the MPQ file key, so
     * its iteration order, and with it the rebuilt block table layout, was not
     * reproducible.
     */
    @Test
    public void p0_6_rebuildIsReproducible() throws IOException {
        final byte[] payload = "deterministic".getBytes(StandardCharsets.UTF_8);
        String first = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Path mpq = TestResources.mpqCopy("normalMap");
            try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                editor.insertByteArray("a.txt", payload);
                editor.insertByteArray("b.txt", payload);
                editor.insertByteArray("c.txt", payload);
            }
            final String digest = TestHelper.md5(mpq);
            if (first == null) {
                first = digest;
            } else {
                Assert.assertEquals(digest, first, "rebuild is not reproducible");
            }
        }
    }

    /**
     * P0-6: list file entries keep insertion order and are encoded as UTF-8
     * regardless of the platform default charset.
     */
    @Test
    public void p0_6_listfileKeepsOrderAndUsesUtf8() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("zzz-later.txt", new byte[]{1});
            editor.insertByteArray("aaa-earlier.txt", new byte[]{1});
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            String listfile = new String(editor.extractFileAsBytes("(listfile)"), StandardCharsets.UTF_8);
            Assert.assertTrue(listfile.indexOf("zzz-later.txt") < listfile.indexOf("aaa-earlier.txt"),
                "listfile lost insertion order:\n" + listfile);
            Assert.assertTrue(listfile.endsWith("\r\n"), "listfile entries must be CRLF terminated");
        }
    }

    // ---------------------------------------------------------------- P0-9

    /**
     * P0-9: the rebuild wrote {@code archiveSize = currentPos + 1}, one byte
     * more than it had actually written.
     */
    @Test
    public void p0_9_archiveSizeMatchesWhatWasWritten() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.setKeepHeaderOffset(false);
            editor.insertByteArray("sized.txt", "exact".getBytes(StandardCharsets.UTF_8));
        }

        final byte[] archive = Files.readAllBytes(mpq);
        final long declared = java.nio.ByteBuffer.wrap(archive)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8) & 0xFFFFFFFFL;
        Assert.assertEquals(declared, archive.length,
            "header archive size disagrees with the file length");
    }

    // --------------------------------------------------------------- P0-12

    /**
     * P0-12: every extract path used to close the caller's stream, truncating
     * anyone writing several files into one stream.
     */
    @Test
    public void p0_12_extractionDoesNotCloseCallerStream() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            final ClosingTracker sink = new ClosingTracker();
            for (String name : editor.getFileNames()) {
                editor.extractFile(name, sink);
                Assert.assertFalse(sink.closed, "extraction closed the caller's stream on " + name);
            }
            Assert.assertTrue(sink.size() > 0, "nothing was written");
        }
    }

    private static final class ClosingTracker extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    // --------------------------------------------------------------- P0-13

    /**
     * P0-13: {@code insertByteArray} kept a reference to the caller's array, so
     * mutating it afterwards changed what the rebuild wrote.
     */
    @Test
    public void p0_13_insertByteArrayCopiesItsInput() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final byte[] caller = "original".getBytes(StandardCharsets.UTF_8);

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("aliased.txt", caller);
            Arrays.fill(caller, (byte) 'X');
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertEquals(new String(editor.extractFileAsBytes("aliased.txt"), StandardCharsets.UTF_8),
                "original");
        }
    }

    // --------------------------------------------------------------- P0-14

    /**
     * P0-14 (issue #38): opening a writable archive passed
     * {@code StandardOpenOption.CREATE}, so probing a path that did not exist
     * left a zero byte file behind.
     */
    @Test
    public void p0_14_openingMissingArchiveDoesNotCreateIt() {
        Path missing = TestResources.scratchDir("missing").resolve("not-there.w3x");

        Assert.expectThrows(JMpqException.class, () -> new JMpqEditor(missing, MPQOpenOption.FORCE_V0));
        Assert.assertFalse(Files.exists(missing), "opening a missing archive created it");
    }

    // --------------------------------------------------------------- P0-15

    /**
     * P0-15: zero-length files were handled by a {@code sectorCount == 1} hack
     * that conflated "has no sector offset table" with "is empty".
     */
    @Test
    public void p0_15_zeroLengthFileRoundTrips() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("empty.txt", new byte[0]);
            editor.insertByteArray("nonempty.txt", "x".getBytes(StandardCharsets.UTF_8));
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.hasFile("empty.txt"));
            Assert.assertEquals(editor.extractFileAsBytes("empty.txt").length, 0);
            Assert.assertEquals(editor.extractFileAsBytes("nonempty.txt"), "x".getBytes(StandardCharsets.UTF_8));

            // Extraction of an empty file must still produce a file.
            Path out = TestResources.scratchDir("empty-extract").resolve("empty.txt");
            editor.extractFile("empty.txt", out.toFile());
            Assert.assertTrue(Files.exists(out));
            Assert.assertEquals(Files.size(out), 0);
        }
    }

    /**
     * A file exactly one sector long, and one a single byte over, are the
     * boundary cases the sector-count arithmetic gets wrong most easily.
     */
    @Test
    public void p0_15_sectorBoundarySizesRoundTrip() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final int sectorSize;
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            sectorSize = editor.getSectorSize();
        }

        final byte[] exact = pseudoRandom(sectorSize);
        final byte[] overByOne = pseudoRandom(sectorSize + 1);
        final byte[] underByOne = pseudoRandom(sectorSize - 1);

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("exact.bin", exact);
            editor.insertByteArray("over.bin", overByOne);
            editor.insertByteArray("under.bin", underByOne);
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertEquals(editor.extractFileAsBytes("exact.bin"), exact);
            Assert.assertEquals(editor.extractFileAsBytes("over.bin"), overByOne);
            Assert.assertEquals(editor.extractFileAsBytes("under.bin"), underByOne);
        }
    }

    /** Deterministic, poorly compressible filler. */
    private static byte[] pseudoRandom(int length) {
        final byte[] out = new byte[length];
        int state = 0x12345678;
        for (int i = 0; i < length; i++) {
            state = state * 1103515245 + 12345;
            out[i] = (byte) (state >>> 16);
        }
        return out;
    }

    // ----------------------------------------------------------- P0-8/P0-11

    /**
     * P0-8: header and table fields came straight from an untrusted file and
     * flowed into allocations. A truncated or nonsensical archive must be
     * rejected with a diagnostic.
     */
    @Test
    public void p0_8_malformedArchivesAreRejectedWithDiagnostics() throws IOException {
        // A header claiming a huge hash table in a tiny file.
        final byte[] archive = new byte[512];
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(archive).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putInt(JMpqEditor.ARCHIVE_HEADER_MAGIC);
        header.putInt(32);              // header size
        header.putInt(archive.length);  // archive size
        header.putShort((short) 0);     // format version
        header.putShort((short) 3);     // sector size shift
        header.putInt(32);              // hash table position
        header.putInt(64);              // block table position
        header.putInt(0x00040000);      // hash table entries: 256K of them
        header.putInt(1);               // block table entries

        JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> new JMpqEditor(archive, MPQOpenOption.READ_ONLY));
        Assert.assertTrue(thrown.getMessage().contains("Hash table"), thrown.getMessage());
    }

    /**
     * P0-8: a nonsense header size must be reported, and the message must point
     * at the workaround rather than leaving the caller guessing.
     */
    @Test
    public void p0_8_badHeaderSizeIsReported() {
        Path mpq = TestResources.mpqCopy("listfileTooLong");
        JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> new JMpqEditor(mpq, MPQOpenOption.READ_ONLY));
        Assert.assertTrue(thrown.getMessage().contains("FORCE_V0"), thrown.getMessage());
    }

    /**
     * P0-11: exceptions must carry their cause. The old code built a new
     * exception from {@code e.getMessage()} and dropped the stack trace.
     */
    @Test
    public void p0_11_exceptionsPreserveTheirCause() {
        Path missing = TestResources.scratchDir("cause").resolve("nope.w3x");
        JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> new JMpqEditor(missing, MPQOpenOption.READ_ONLY));
        // Either a cause, or a message that names the file; never a bare
        // message with the context thrown away.
        Assert.assertTrue(thrown.getMessage().contains("nope.w3x"), thrown.getMessage());
    }

    /**
     * P0-11: {@code hasFile} must answer without using exceptions as control
     * flow, and must never throw for an absent file.
     */
    @Test
    public void p0_11_hasFileDoesNotThrow() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.hasFile("definitely\\not\\here.txt"));
            Assert.assertTrue(editor.hasFile("(listfile)"));
        }
    }

    // ---------------------------------------------------------------- P0-10

    /**
     * P0-10: a read-only editor must refuse writes rather than accept them and
     * discard the result at close.
     */
    @Test
    public void p0_10_readOnlyEditorRefusesWrites() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.isCanWrite());
            Assert.expectThrows(NonWritableChannelException.class,
                () -> editor.insertByteArray("nope.txt", new byte[1]));
            Assert.expectThrows(NonWritableChannelException.class, () -> editor.deleteFile("(listfile)"));
        }
    }

    // ----------------------------------------------------------- in-memory

    /**
     * An archive opened from a byte array has no file to write back to, so the
     * rebuilt image must be retrievable.
     */
    @Test
    public void inMemoryArchiveExposesItsRebuiltImage() throws IOException {
        final byte[] source = Files.readAllBytes(TestResources.mpqCopy("normalMap"));
        final byte[] rebuilt;

        JMpqEditor editor = new JMpqEditor(source, MPQOpenOption.FORCE_V0);
        editor.insertByteArray("in-memory.txt", "hello".getBytes(StandardCharsets.UTF_8));
        editor.close();
        rebuilt = editor.getOutputByteArray();

        Assert.assertNotNull(rebuilt, "rebuild produced no image");
        try (JMpqEditor reopened = new JMpqEditor(rebuilt, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertEquals(new String(reopened.extractFileAsBytes("in-memory.txt"), StandardCharsets.UTF_8),
                "hello");
        }
    }

    /**
     * Compressing with every {@link RecompressOptions} variant must round trip.
     */
    @Test
    public void everyCompressionSettingRoundTrips() throws IOException {
        final byte[] payload = TestResources.bytes("war3map.j");

        for (boolean recompress : new boolean[]{false, true}) {
            for (boolean zopfli : new boolean[]{false, true}) {
                if (!recompress && zopfli) {
                    continue;
                }
                final RecompressOptions options = new RecompressOptions(recompress);
                options.useZopfli = zopfli;
                options.iterations = 1;

                Path mpq = TestResources.mpqCopy("normalMap");
                try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
                    editor.insertByteArray("payload.j", payload, true);
                    editor.close(true, false, options);
                }
                try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
                    Assert.assertEquals(editor.extractFileAsBytes("payload.j"), payload,
                        "recompress=" + recompress + " zopfli=" + zopfli);
                }
            }
        }
    }

    // -------------------------------------------------- review follow-ups

    /**
     * A sparse stream whose control runs stop short of its declared length must
     * be rejected. Returning the buffer anyway hands back the missing tail as
     * zeros at exactly the length the caller expects, so the corruption would
     * pass every downstream check.
     */
    @Test
    public void truncatedSparseStreamIsRejected() {
        // Declares 64 bytes but only describes a 4 byte zero run.
        final byte[] sparse = new byte[]{0, 0, 0, 64, 0x01};
        final byte[] sector = withTypeByte(0x20, sparse);

        JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> CompressionUtil.decompress(sector, sector.length, 64));
        Assert.assertTrue(thrown.getMessage().contains("produced"), thrown.getMessage());
    }

    /**
     * List file entries keep their leading and trailing whitespace: it is part
     * of what the name hashes to, so trimming produces a name that no longer
     * resolves and a rebuild would discard the file. Protected archives rely on
     * this to plant near-duplicate entries.
     */
    @Test
    public void listfileEntriesKeepSignificantWhitespace() throws IOException {
        final String padded = "war3mapImported\trailing .txt";
        Path mpq = TestResources.mpqCopy("normalMap");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray(padded, "kept".getBytes(StandardCharsets.UTF_8));
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.hasFile(padded), "name with whitespace did not survive the rebuild");
            Assert.assertEquals(new String(editor.extractFileAsBytes(padded), StandardCharsets.UTF_8), "kept");
            Assert.assertTrue(editor.getListfileEntries().contains(padded),
                "listfile lost the whitespace: " + editor.getListfileEntries());
        }
    }

    /**
     * The hash table must index the way StormLib does, {@code hash & (capacity
     * - 1)}, so that a name written by StormLib is found at the bucket StormLib
     * would probe from.
     */
    @Test
    public void hashTableIndexingMatchesStormlib() throws IOException {
        // A capacity that is not a power of two: mask and remainder disagree,
        // and the mask rule is the one the format uses.
        final HashTable table = new HashTable(3);
        table.setFileBlockIndex("war3map.j", HashTable.DEFAULT_LOCALE, 0);
        Assert.assertTrue(table.hasFile("war3map.j"));
        Assert.assertEquals(table.getBlockIndexOfFile("war3map.j"), 0);

        // And the ordinary power-of-two case still round trips.
        final HashTable normal = new HashTable(16);
        normal.setFileBlockIndex("war3map.w3i", HashTable.DEFAULT_LOCALE, 5);
        Assert.assertEquals(normal.getBlockIndexOfFile("war3map.w3i"), 5);
    }

    /**
     * A writable in-memory archive must not write back into the caller's array.
     * The channel wraps the array it is given and grows it only when it must,
     * so a rebuilt image that fits inside the original would otherwise be
     * written in place.
     */
    @Test
    public void inMemoryRebuildLeavesTheCallersArrayAlone() throws IOException {
        final byte[] caller = Files.readAllBytes(TestResources.mpqCopy("normalMap"));
        final byte[] pristine = caller.clone();

        JMpqEditor editor = new JMpqEditor(caller, MPQOpenOption.FORCE_V0);
        // Delete files so the rebuilt image is smaller than the original and
        // therefore fits inside the array the caller handed over.
        for (String name : new ArrayList<>(editor.getFileNames())) {
            editor.deleteFile(name);
        }
        editor.close();

        Assert.assertEquals(caller, pristine, "rebuild wrote into the caller's array");
        Assert.assertNotNull(editor.getOutputByteArray());
        Assert.assertTrue(editor.getOutputByteArray().length < pristine.length,
            "expected the rebuilt image to be smaller, so the in-place case is exercised");
    }

    /**
     * Sector counting must not overflow. Both arguments are {@code int}, so the
     * obvious {@code (size + sectorSize - 1) / sectorSize} goes negative for a
     * file within one sector of {@link Integer#MAX_VALUE} -- about 2 GiB with
     * 4 KiB sectors, or just over 1 GiB at the largest sector size the format
     * allows. The archive would then be unreadable despite being valid.
     */
    @Test
    public void sectorCountDoesNotOverflow() {
        final int fourKiB = 4096;
        final int oneGiB = 512 << 21;

        Assert.assertEquals(MpqFile.sectorCount(0, fourKiB), 0);
        Assert.assertEquals(MpqFile.sectorCount(1, fourKiB), 1);
        Assert.assertEquals(MpqFile.sectorCount(fourKiB, fourKiB), 1);
        Assert.assertEquals(MpqFile.sectorCount(fourKiB + 1, fourKiB), 2);

        // The cases that overflowed.
        Assert.assertEquals(MpqFile.sectorCount(Integer.MAX_VALUE, fourKiB), 524288);
        Assert.assertEquals(MpqFile.sectorCount(Integer.MAX_VALUE, oneGiB), 2);
        Assert.assertEquals(MpqFile.sectorCount(Integer.MAX_VALUE - 1, fourKiB), 524288);
        Assert.assertEquals(MpqFile.sectorCount(oneGiB + 1, oneGiB), 2);

        // A count can never exceed the size, so it always fits in an int.
        for (int size : new int[]{1, 4095, 4096, oneGiB, Integer.MAX_VALUE}) {
            Assert.assertTrue(MpqFile.sectorCount(size, fourKiB) > 0,
                "non-positive sector count for size " + size);
            Assert.assertTrue(MpqFile.sectorCount(size, fourKiB) <= size,
                "sector count exceeds size for " + size);
        }

        Assert.expectThrows(IllegalArgumentException.class, () -> MpqFile.sectorCount(-1, fourKiB));
        Assert.expectThrows(IllegalArgumentException.class, () -> MpqFile.sectorCount(1, 0));
    }

    /**
     * A name containing a forward slash is a distinct file and must survive a
     * rebuild. Folding it to a backslash renamed the entry, so it no longer
     * resolved and a writable rebuild discarded it as a stale list file entry.
     */
    @Test
    public void forwardSlashNamesAreDistinctAndSurviveRebuild() throws IOException {
        final String slash = "dir/file.txt";
        final String backslash = "dir\\file.txt";
        Path mpq = TestResources.mpqCopy("normalMap");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray(slash, "forward".getBytes(StandardCharsets.UTF_8));
            editor.insertByteArray(backslash, "back".getBytes(StandardCharsets.UTF_8));
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.hasFile(slash), "forward slash name lost on rebuild");
            Assert.assertTrue(editor.hasFile(backslash), "backslash name lost on rebuild");
            Assert.assertEquals(new String(editor.extractFileAsBytes(slash), StandardCharsets.UTF_8), "forward");
            Assert.assertEquals(new String(editor.extractFileAsBytes(backslash), StandardCharsets.UTF_8), "back");
        }
    }


    /**
     * An archive with no usable list file is downgraded to read-only, and
     * supplying an external list file is the documented way to recover it. That
     * flow was dead: setExternalListfile refused to run on a read-only editor,
     * so it turned away exactly the archives it exists for.
     */
    @Test
    public void externalListfileRestoresWritability() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        Path listfile = TestResources.file("listfile.txt");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.isCanWrite(), "an archive with no listfile should not be writable");

            editor.setExternalListfile(listfile.toFile());
            Assert.assertTrue(editor.isCanWrite(), "external listfile did not restore writability");
            Assert.assertFalse(editor.getListfileEntries().isEmpty(), "no entries were applied");

            editor.insertByteArray("recovered.txt", "yes".getBytes(StandardCharsets.UTF_8));
        }

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.hasFile("recovered.txt"));
            Assert.assertEquals(new String(editor.extractFileAsBytes("recovered.txt"), StandardCharsets.UTF_8),
                "yes");
        }
    }

    /**
     * A READ_ONLY editor must stay read-only whatever list file it is handed.
     */
    @Test
    public void externalListfileCannotOverrideReadOnly() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            editor.setExternalListfile(TestResources.file("listfile.txt").toFile());
            Assert.assertFalse(editor.isCanWrite(), "READ_ONLY was overridden by an external listfile");
        }
    }

    /**
     * A list file name made only of whitespace is still a representable MPQ
     * name, so the parser must keep it rather than filter it as blank. A
     * spurious whitespace line is harmless: it does not resolve in the hash
     * table, so completeness checking prunes it.
     */
    @Test
    public void whitespaceOnlyListfileNamesAreKept() {
        final byte[] raw = ("real.txt" + System.lineSeparator()
            + "   " + System.lineSeparator()
            + System.lineSeparator()
            + "other.txt" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        final systems.crigges.jmpq3.Listfile listfile = new systems.crigges.jmpq3.Listfile(raw);

        Assert.assertTrue(listfile.containsFile("real.txt"));
        Assert.assertTrue(listfile.containsFile("other.txt"));
        Assert.assertTrue(listfile.containsFile("   "), "whitespace-only name was dropped");
        // The genuinely empty line must not become an entry.
        Assert.assertEquals(listfile.size(), 3, listfile.getFiles().toString());
    }


    /**
     * The codecs are ports of C code and report bad input with whatever
     * unchecked exception came to hand. Those must surface as JMpqException, or
     * a caller that catches the library's own exception type sees an
     * IllegalStateException instead.
     */
    @Test
    public void corruptSectorDataSurfacesAsJMpqException() {
        // A deflate type byte followed by bytes that are not a deflate stream.
        final byte[] garbage = new byte[64];
        Arrays.fill(garbage, (byte) 0xAB);
        final byte[] sector = withTypeByte(0x02, garbage);

        Assert.expectThrows(JMpqException.class,
            () -> CompressionUtil.decompress(sector, sector.length, 256));

        // And the imploded path, which bypasses the mask dispatch entirely.
        Assert.expectThrows(JMpqException.class,
            () -> CompressionUtil.explode(garbage, garbage.length, 256));
    }

    /**
     * Extracting everything is best effort: one damaged file must not cost the
     * caller the rest of the archive. An unchecked codec failure used to abort
     * the whole sweep.
     */
    @Test
    public void corruptFileDoesNotAbortExtractAll() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final int expectedNames;
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            expectedNames = editor.getFileNames().size();
        }
        Assert.assertTrue(expectedNames > 3, "fixture should hold several files");

        // Corrupt a stretch of the data area, well past the header and well
        // before the hash table, so some file's sectors become undecodable.
        final byte[] image = Files.readAllBytes(mpq);
        Arrays.fill(image, 0x2000, 0x2400, (byte) 0x5A);
        Files.write(mpq, image);

        Path out = TestResources.scratchDir("corrupt-extract");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            // Must not throw, despite the damage.
            editor.extractAllFiles(out.toFile());
        }

        try (var extracted = Files.walk(out)) {
            long files = extracted.filter(Files::isRegularFile).count();
            Assert.assertTrue(files > 0, "the damage aborted the whole sweep");
        }
    }


    /**
     * An archive naming a file outside the destination must have that entry
     * refused, without costing the caller the rest of the archive. Refusing it
     * used to throw before the per-file guard, so a single traversal entry
     * denied extraction of everything -- and archives carrying one are exactly
     * the archives where the rest still matters.
     */
    @Test
    public void traversalEntryIsSkippedNotFatal() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final String escaping = "..\\evil.txt";

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray(escaping, "pwned".getBytes(StandardCharsets.UTF_8));
            editor.insertByteArray("safe.txt", "fine".getBytes(StandardCharsets.UTF_8));
        }

        Path out = TestResources.scratchDir("traversal");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.hasFile(escaping), "fixture setup failed");
            // Must not throw despite the hostile entry.
            editor.extractAllFiles(out.toFile());
        }

        Assert.assertTrue(Files.exists(out.resolve("safe.txt")),
            "the traversal entry aborted extraction of the safe files");
        Assert.assertFalse(Files.exists(out.getParent().resolve("evil.txt")),
            "a file escaped the destination directory");
    }


    /**
     * Every class of the library, discovered from the compiled output rather
     * than a hand-maintained list, so a newly added class cannot slip past the
     * global-state check.
     */
    private static List<String> libraryClasses() throws Exception {
        final Path root = Path.of(JMpqEditor.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        Assert.assertTrue(Files.isDirectory(root), "expected exploded classes, got " + root);

        try (var paths = Files.walk(root)) {
            final List<String> classes = paths
                .filter(p -> p.toString().endsWith(".class"))
                .map(p -> root.relativize(p).toString()
                    .replace('\\', '.').replace('/', '.')
                    .replaceAll("\\.class$", ""))
                .filter(name -> name.startsWith("systems.crigges.jmpq3."))
                .sorted()
                .toList();
            Assert.assertTrue(classes.size() > 15, "class discovery found only " + classes);
            return classes;
        }
    }
}
