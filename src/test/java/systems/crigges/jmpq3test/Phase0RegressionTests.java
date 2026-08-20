package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.MPQOpenOption;
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
        Assert.assertEquals(MpqNames.canonical("Units/Test.txt"), MpqNames.canonical("units\\TEST.TXT"));
        Assert.assertEquals(MpqNames.fileKey("Units/Test.txt"), MpqNames.fileKey("UNITS\\test.txt"));

        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("Units\\Case.txt", "a".getBytes(StandardCharsets.UTF_8));
            // Same file by MPQ rules, so a non-overriding insert must be refused
            // and a delete must find it.
            Assert.expectThrows(IllegalArgumentException.class,
                () -> editor.insertByteArray("units/CASE.TXT", "b".getBytes(StandardCharsets.UTF_8)));
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
                options.iterations = 4;

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
