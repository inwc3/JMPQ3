package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqAttributes;
import org.inwc3.jmpq.MpqFileEntry;
import org.inwc3.jmpq.MpqHeader;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqUserData;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Phase 2: sector checksums, generated attributes, the hi-block table and the
 * user data header.
 */
public class Phase2FormatTests {

    /** Enough content for several sectors at the default 4 KiB. */
    private static byte[] incompressible(int length, long seed) {
        final byte[] content = new byte[length];
        new Random(seed).nextBytes(content);
        return content;
    }

    private static int crc32(byte[] content) {
        final CRC32 digest = new CRC32();
        digest.update(content);
        return (int) digest.getValue();
    }

    // ------------------------------------------------------- P2-3 sector CRC

    /**
     * A checksummed archive reads back byte for byte, and says so in its flags.
     * <p>
     * The {@code (listfile)} matters as much as the caller's files here: the
     * writer encrypts internal files, so this is the case where a file's sectors
     * are encrypted while its checksum chunk is not — StormLib writes that chunk
     * without encrypting and loads it with key 0. Getting that wrong decodes the
     * sectors correctly and the checksums as noise.
     */
    @Test
    public void checksummedFilesRoundTrip() throws IOException {
        final byte[] big = incompressible(11_000, 1);
        final byte[] small = "small enough for one sector".getBytes(StandardCharsets.UTF_8);

        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withSectorChecksums(true))
            .put("big.bin", big)
            .put("small.txt", small)
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read("big.bin"), big);
            Assert.assertEquals(archive.read("small.txt"), small);

            for (MpqFileEntry entry : archive.entries()) {
                Assert.assertTrue(entry.has(MpqFileEntry.FLAG_SECTOR_CRC),
                    entry.name() + " should carry checksums: " + entry.flagsToString());
            }
            // The encrypted-sectors-plain-checksums combination, exercised.
            final MpqFileEntry listfile = archive.entry("(listfile)").orElseThrow();
            Assert.assertTrue(listfile.isEncrypted());
            Assert.assertTrue(listfile.has(MpqFileEntry.FLAG_SECTOR_CRC));
            Assert.assertTrue(new String(archive.read("(listfile)"), StandardCharsets.UTF_8)
                .contains("big.bin"));
        }
    }

    /** An empty file has no sectors, so it cannot carry a checksum. */
    @Test
    public void anEmptyFileCarriesNoChecksum() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withSectorChecksums(true))
            .put("empty.txt", new byte[0])
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final MpqFileEntry entry = archive.entry("empty.txt").orElseThrow();
            Assert.assertFalse(entry.has(MpqFileEntry.FLAG_SECTOR_CRC), entry.flagsToString());
            Assert.assertEquals(archive.read("empty.txt").length, 0);
        }
    }

    /**
     * The point of the whole feature: damaged data is reported instead of being
     * handed back. Flipping a byte inside the first sector's payload is caught
     * before decompression, because that is where the checksum is taken.
     */
    @Test
    public void damageIsDetectedRatherThanReturned() throws IOException {
        final byte[] content = incompressible(9_000, 2);
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withSectorChecksums(true))
            .put("data.bin", content)
            .toByteArray();

        final int corruptAt = payloadStart(image, "data.bin") + 3;
        image[corruptAt] ^= 0x5A;

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final JMpqException thrown = Assert.expectThrows(JMpqException.class,
                () -> archive.read("data.bin"));
            Assert.assertTrue(thrown.getMessage().contains("checksum"), thrown.getMessage());
        }

        // Turning verification off recovers whatever is still intact, which is
        // occasionally what you want. It must not throw, and must not silently
        // pretend the bytes are right.
        try (MpqArchive archive = MpqArchive.open(image,
            MpqOpenOptions.defaults().withSectorChecksumVerification(false))) {
            Assert.assertNotEquals(archive.read("data.bin"), content,
                "the byte really was corrupted");
        }
    }

    /**
     * A verbatim copy keeps the checksums valid.
     * <p>
     * This is the path where getting the checksum chunk wrong is permanent: the
     * copy clears the encryption flags but keeps {@code SECTOR_CRC}, so a chunk
     * mangled on the way through is written as authoritative and every later
     * read of that file fails.
     */
    @Test
    public void aVerbatimCopyKeepsChecksumsValid() throws IOException {
        final byte[] content = incompressible(13_000, 3);
        final byte[] first = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withSectorChecksums(true))
            .put("carried.bin", content)
            .toByteArray();

        final byte[] second;
        try (MpqArchive archive = MpqArchive.open(first, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.entry("carried.bin").orElseThrow()
                .has(MpqFileEntry.FLAG_SECTOR_CRC));
            second = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults()).toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(second, MpqOpenOptions.defaults())) {
            final MpqFileEntry entry = archive.entry("carried.bin").orElseThrow();
            Assert.assertTrue(entry.has(MpqFileEntry.FLAG_SECTOR_CRC),
                "the copy preserved the flag, so it must preserve valid checksums");
            Assert.assertFalse(entry.isEncrypted(), "the copy is stored plain");
            Assert.assertEquals(archive.read("carried.bin"), content);
        }
    }

    /**
     * Asking for checksums applies them to carried-over files too, by
     * re-encoding rather than copying. Otherwise the option quietly means "on
     * whichever files happened to be re-encoded anyway", and the archive ends
     * up half checksummed.
     */
    @Test
    public void checksumsAreAddedToCarriedOverFilesToo() throws IOException {
        final byte[] content = incompressible(9_500, 5);
        final byte[] without = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("carried.bin", content)
            .toByteArray();

        try (MpqArchive source = MpqArchive.open(without, MpqOpenOptions.defaults())) {
            Assert.assertFalse(source.entry("carried.bin").orElseThrow()
                .has(MpqFileEntry.FLAG_SECTOR_CRC), "nothing to carry over yet");

            final byte[] with = MpqArchiveWriter
                .from(source, MpqWriteOptions.defaults().withSectorChecksums(true))
                .toByteArray();

            try (MpqArchive rebuilt = MpqArchive.open(with, MpqOpenOptions.defaults())) {
                Assert.assertTrue(rebuilt.entry("carried.bin").orElseThrow()
                    .has(MpqFileEntry.FLAG_SECTOR_CRC), "the rebuild should have added them");
                Assert.assertEquals(rebuilt.read("carried.bin"), content);
            }
        }
    }

    /** Where a file's first sector payload begins, past its offset table. */
    private static int payloadStart(byte[] image, String name) throws IOException {
        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final MpqFileEntry entry = archive.entry(name).orElseThrow();
            final int sectors = (entry.normalSize() + archive.header().sectorSize() - 1)
                / archive.header().sectorSize();
            final int tableBytes = (sectors + 1 + 1) * 4;
            return (int) (archive.header().headerOffset() + entry.filePosition() + tableBytes);
        }
    }

    /**
     * The seed, pinned against known values.
     * <p>
     * StormLib checksums sectors with {@code adler32(0, ...)}, and a standard
     * Adler-32 starts at 1 instead. The two differ by 1 in the low half and by
     * the byte count in the high half — for every input, which means a reader
     * and writer that both get it wrong agree with each other and with nothing
     * else. That is exactly what happened here, and only
     * {@code tools/mpqref.py} noticed. These constants come from
     * {@code zlib.adler32(data, 0)}.
     */
    @Test
    public void sectorChecksumsUseTheSeedStormLibUses() throws Exception {
        Assert.assertEquals(adler32("abc".getBytes(StandardCharsets.UTF_8)), 0x024A0126,
            "seeding with 1 would give 0x024D0127");
        Assert.assertEquals(adler32(new byte[0]), 0);
        final byte[] long_ = new byte[10_000];
        java.util.Arrays.fill(long_, (byte) 'a');
        Assert.assertEquals(adler32(long_), 0x78ABCDE2,
            "long enough to cross the block boundary the accumulator folds at");

        // And it is not what java.util.zip.Adler32 produces, which is the trap.
        final java.util.zip.Adler32 standard = new java.util.zip.Adler32();
        standard.update("abc".getBytes(StandardCharsets.UTF_8));
        Assert.assertNotEquals((int) standard.getValue(), 0x024A0126);
    }

    /** Reaches the package-private checksum used by both reader and writer. */
    private static int adler32(byte[] data) throws Exception {
        final Class<?> type = Class.forName("org.inwc3.jmpq.MpqChecksums");
        final java.lang.reflect.Method method = type.getDeclaredMethod("adler32", byte[].class);
        method.setAccessible(true);
        return (int) method.invoke(null, (Object) data);
    }

    // ------------------------------------------------------- P2-4 attributes

    /**
     * Generated attributes describe every block, with a CRC32 taken over each
     * file's decoded content — which is what StormLib records and what issue
     * #11 asked for.
     */
    @Test
    public void generatedAttributesDescribeEveryBlock() throws IOException {
        final long pinned = 1_600_000_000_000L;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("one.txt", "first".getBytes(StandardCharsets.UTF_8));
        files.put("two.bin", incompressible(6_000, 4));
        files.put("three.txt", "third".getBytes(StandardCharsets.UTF_8));

        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults()
            .withAttributes(true)
            .withAttributesTimestamp(pinned));
        files.forEach(writer::put);
        final byte[] image = writer.toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final MpqAttributes attributes = archive.attributes().orElseThrow();
            Assert.assertEquals(attributes.version(), MpqAttributes.VERSION);
            Assert.assertEquals(attributes.entries(), archive.header().blockTableEntries());
            Assert.assertFalse(attributes.truncated());

            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                final int block = archive.entry(file.getKey()).orElseThrow().blockIndex();
                Assert.assertEquals(attributes.crc32Of(block), crc32(file.getValue()),
                    "crc of " + file.getKey());
                Assert.assertEquals(attributes.fileTimeOf(block),
                    MpqAttributes.toFileTime(pinned), "timestamp of " + file.getKey());
            }

            // The listfile is described too; the attributes file cannot describe
            // itself, so its own slot stays at "not recorded".
            final int listfile = archive.entry("(listfile)").orElseThrow().blockIndex();
            Assert.assertEquals(attributes.crc32Of(listfile),
                crc32(archive.read("(listfile)")));
            final int own = archive.entry(MpqAttributes.NAME).orElseThrow().blockIndex();
            Assert.assertEquals(attributes.crc32Of(own), 0, "its own checksum cannot exist");
        }
    }

    /** Spare block slots get a zero checksum, which reads as "not recorded". */
    @Test
    public void spareBlockSlotsAreDescribedAsUnrecorded() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withAttributes(true).withExtraBlockEntries(5))
            .put("a.txt", "a".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final MpqAttributes attributes = archive.attributes().orElseThrow();
            // One file, the listfile, the attributes file, and five spares.
            Assert.assertEquals(attributes.entries(), 3 + 5);
            Assert.assertEquals(archive.header().blockTableEntries(), 3 + 5);
            for (int i = 3; i < 8; i++) {
                Assert.assertEquals(attributes.crc32Of(i), 0, "spare slot " + i);
                Assert.assertEquals(attributes.fileTimeOf(i), 0L, "spare slot " + i);
            }
        }
    }

    /** A pinned timestamp makes the build reproducible. */
    @Test
    public void aPinnedTimestampMakesTheBuildReproducible() throws IOException {
        final MpqWriteOptions options = MpqWriteOptions.defaults()
            .withAttributes(true)
            .withAttributesTimestamp(1_700_000_000_000L);

        final byte[] first = MpqArchiveWriter.create(options)
            .put("a.txt", "a".getBytes(StandardCharsets.UTF_8)).toByteArray();
        final byte[] second = MpqArchiveWriter.create(options)
            .put("a.txt", "a".getBytes(StandardCharsets.UTF_8)).toByteArray();

        Assert.assertEquals(first, second, "identical input must give identical bytes");
    }

    /**
     * Generating attributes and supplying them is refused rather than producing
     * two entries under one name. Supplying them alone stays legal, which is how
     * a caller preserved them before generation existed.
     */
    @Test
    public void generatingAndSupplyingAttributesIsRefused() throws IOException {
        final byte[] supplied = MpqAttributes.build(new int[2], new long[2]);

        final MpqArchiveWriter both = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withAttributes(true))
            .put(MpqAttributes.NAME, supplied);
        final JMpqException thrown = Assert.expectThrows(JMpqException.class, both::toByteArray);
        Assert.assertTrue(thrown.getMessage().contains("two entries"), thrown.getMessage());

        final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put(MpqAttributes.NAME, supplied)
            .toByteArray();
        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read(MpqAttributes.NAME), supplied);
        }
    }

    /**
     * Attributes are advisory: an archive carrying ones that will not parse is
     * still a good archive, and must open rather than being rejected.
     */
    @Test
    public void unparseableAttributesDoNotStopTheArchiveOpening() throws IOException {
        final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put(MpqAttributes.NAME, new byte[]{100, 0, 0, 0, 3, 0, 0, 0, 1, 2, 3})
            .put("real.txt", "kept".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.attributes().isEmpty());
            Assert.assertEquals(archive.read("real.txt"), "kept".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------- P2-2 hi-block table

    /**
     * A hi-block table of zeroes changes nothing, which is the only shape a
     * small archive can legitimately have: the table supplies bits 32 to 47 of
     * each file position, so a non-zero entry means an archive past 4 GiB.
     */
    @Test
    public void aZeroHiBlockTableLeavesPositionsAlone() throws IOException {
        final byte[] plain = MpqArchiveWriter.create(MpqWriteOptions.defaults()
                .withFormatVersion(1))
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        final byte[] withTable = attachHiBlockTable(plain, new int[]{0, 0});

        try (MpqArchive archive = MpqArchive.open(withTable, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.header().hasHiBlockTable());
            Assert.assertEquals(archive.read("a.txt"), "content".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A non-zero entry really is applied. There is no way to store data 4 GiB
     * into a test fixture, so the proof is that the read is attempted there:
     * the failure names an offset above 4 GiB, which it can only do if the high
     * word reached the file position.
     */
    @Test
    public void aNonZeroHiBlockEntryMovesTheFilePosition() throws IOException {
        final byte[] plain = MpqArchiveWriter.create(MpqWriteOptions.defaults()
                .withFormatVersion(1))
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        final byte[] withTable = attachHiBlockTable(plain, new int[]{1, 0});

        try (MpqArchive archive = MpqArchive.open(withTable, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.entry("a.txt").orElseThrow().filePosition() >>> 32, 1L,
                "the high word belongs in bits 32 and up");
            final JMpqException thrown = Assert.expectThrows(JMpqException.class,
                () -> archive.read("a.txt"));
            Assert.assertTrue(thrown.getMessage().contains("outside"), thrown.getMessage());
        }
    }

    /**
     * An archive claiming a hi-block table it does not hold is read with the low
     * words alone, which is what a version 0 reader would do anyway. Refusing it
     * would lose an archive that is entirely readable.
     */
    @Test
    public void aHiBlockTableOutsideTheFileIsIgnored() throws IOException {
        final byte[] plain = MpqArchiveWriter.create(MpqWriteOptions.defaults()
                .withFormatVersion(1))
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        final ByteBuffer edit = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        final int headerAt = headerOffset(plain);
        edit.putLong(headerAt + 0x20, 0x7FFF_FFFFL);

        try (MpqArchive archive = MpqArchive.open(plain, MpqOpenOptions.defaults())) {
            Assert.assertFalse(archive.header().hasHiBlockTable(), "dropped as implausible");
            Assert.assertTrue(archive.header().malformed());
            Assert.assertEquals(archive.read("a.txt"), "content".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Appends a hi-block table to a version 1 archive and points the header at
     * it. The table is neither encrypted nor compressed, per StormLib.
     */
    private static byte[] attachHiBlockTable(byte[] image, int[] highWords) {
        final byte[] out = new byte[image.length + highWords.length * 2];
        System.arraycopy(image, 0, out, 0, image.length);

        final int headerAt = headerOffset(image);
        final ByteBuffer edit = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < highWords.length; i++) {
            edit.putShort(image.length + i * 2, (short) highWords[i]);
        }
        edit.putLong(headerAt + 0x20, image.length - headerAt);
        return out;
    }

    private static int headerOffset(byte[] image) {
        for (int at = 0; at + 4 <= image.length; at += MpqHeader.ALIGNMENT) {
            if (ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getInt(at)
                == MpqHeader.ARCHIVE_SIGNATURE) {
                return at;
            }
        }
        throw new AssertionError("no header in the test fixture");
    }

    // ------------------------------------------------- P2-1 user data header

    /**
     * A user data header redirects to the archive, and its payload is readable
     * rather than discarded. The pre-2.0 code detected the signature, followed
     * the redirect, and threw the rest away with a TODO where the model should
     * have been.
     */
    @Test
    public void aUserDataHeaderIsParsedAndItsPayloadKept() throws IOException {
        final byte[] payload = "user data goes here".getBytes(StandardCharsets.UTF_8);
        final byte[] inner = MpqArchiveWriter.create(MpqWriteOptions.defaults().withPrefix(false))
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8))
            .toByteArray();
        final byte[] image = withUserData(inner, payload);

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            final MpqUserData userData = archive.userData().orElseThrow();
            Assert.assertEquals(userData.offset(), 0);
            Assert.assertEquals(userData.headerSize(), MpqUserData.SIZE);
            Assert.assertEquals(userData.archiveHeaderOffset(), MpqHeader.ALIGNMENT);
            Assert.assertEquals(archive.header().headerOffset(), MpqHeader.ALIGNMENT);
            Assert.assertEquals(archive.read("a.txt"), "content".getBytes(StandardCharsets.UTF_8));
        }

        // Warcraft III ignores user data headers, and so does forceV0. The
        // archive header is found by scanning instead, at the same place.
        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.userData().isEmpty());
            Assert.assertEquals(archive.header().headerOffset(), MpqHeader.ALIGNMENT);
            Assert.assertEquals(archive.read("a.txt"), "content".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Wraps an archive behind a user data header at offset 0. */
    private static byte[] withUserData(byte[] archive, byte[] payload) {
        final byte[] out = new byte[MpqHeader.ALIGNMENT + archive.length];
        final ByteBuffer edit = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        edit.putInt(MpqHeader.USER_DATA_SIGNATURE);
        edit.putInt(payload.length);
        edit.putInt(MpqHeader.ALIGNMENT);
        edit.putInt(MpqUserData.SIZE);
        edit.put(payload);
        System.arraycopy(archive, 0, out, MpqHeader.ALIGNMENT, archive.length);
        return out;
    }

    // --------------------------------------------- P2-5b decoy header scanning

    /**
     * A decoy {@code MPQ\x1A} in front of the real archive no longer ends the
     * scan. Protected maps plant these precisely so a reader commits to the
     * first signature it sees and then fails on tables that are not there.
     */
    @Test
    public void aDecoyHeaderDoesNotEndTheScan() throws IOException {
        final byte[] real = MpqArchiveWriter.create(MpqWriteOptions.defaults().withPrefix(false))
            .put("a.txt", "content".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        final byte[] image = new byte[MpqHeader.ALIGNMENT + real.length];
        final ByteBuffer edit = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        // A header-shaped decoy whose tables point far outside the file.
        edit.putInt(0, MpqHeader.ARCHIVE_SIGNATURE);
        edit.putInt(4, 32);
        edit.putInt(0x10, 0x7FFF_0000);
        edit.putInt(0x14, 0x7FFF_1000);
        edit.putInt(0x18, 16);
        System.arraycopy(real, 0, image, MpqHeader.ALIGNMENT, real.length);

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.header().headerOffset(), MpqHeader.ALIGNMENT,
                "the scan should have walked past the decoy");
            Assert.assertEquals(archive.read("a.txt"), "content".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------- reference cross-verification

    /**
     * Exports checksummed and attributed archives for
     * {@code tools/mpqref.py verify}, which now checks the Adler-32 of every
     * sector itself. That is the only independent confirmation available that
     * the values this writer records are the values the format calls for, rather
     * than merely values this library agrees with itself about.
     */
    @Test
    public void exportForReferenceVerification() throws IOException {
        final Path out = Path.of("build", "phase2");
        final Path archives = out.resolve("archives");
        Files.createDirectories(archives);

        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("small.txt", "one sector only".getBytes(StandardCharsets.UTF_8));
        files.put("multi.bin", incompressible(20_000, 7));
        files.put("compressible.txt", "abcabcabc".repeat(2_000).getBytes(StandardCharsets.UTF_8));
        files.put("empty.txt", new byte[0]);

        final StringBuilder expected = new StringBuilder("# archive\tname\tsize\tmd5\n");

        final MpqWriteOptions[] shapes = {
            MpqWriteOptions.defaults().withSectorChecksums(true),
            MpqWriteOptions.defaults().withAttributes(true).withAttributesTimestamp(0),
            MpqWriteOptions.defaults().withSectorChecksums(true).withAttributes(true)
                .withAttributesTimestamp(0).withFormatVersion(1),
        };

        for (int shape = 0; shape < shapes.length; shape++) {
            final MpqArchiveWriter writer = MpqArchiveWriter.create(shapes[shape]);
            files.forEach(writer::put);
            final String name = "phase2-" + shape + ".mpq";
            Files.write(archives.resolve(name), writer.toByteArray());

            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                expected.append(name).append('\t').append(file.getKey()).append('\t')
                    .append(file.getValue().length).append('\t')
                    .append(TestHelper.md5(file.getValue())).append('\n');
            }
        }

        Files.writeString(out.resolve("expected.tsv"), expected.toString());
    }
}
