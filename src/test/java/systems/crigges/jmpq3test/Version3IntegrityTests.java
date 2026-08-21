package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqHeader;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Version 3 archives: compressed tables and the MD5 digests.
 * <p>
 * The library cannot write version 3, so these fixtures are built by relaying a
 * version 1 archive out behind a 208-byte header. They are still synthetic
 * rather than StormLib-generated — see P2-2 in {@code AUDIT.md} — but they do
 * drive the real header parse, table load and digest paths rather than only
 * their inputs.
 */
public class Version3IntegrityTests {

    private static final int V3_HEADER_SIZE = 208;

    private static final int DIGEST_SIZE = 16;

    /** Offsets of the six digests within a version 3 header. */
    private static final int MD5_BLOCK_TABLE = 0x70;
    private static final int MD5_HASH_TABLE = 0x80;
    private static final int MD5_HI_BLOCK_TABLE = 0x90;
    private static final int MD5_BET_TABLE = 0xA0;
    private static final int MD5_HET_TABLE = 0xB0;
    private static final int MD5_HEADER = 0xC0;

    /** Compression-type byte for deflate. */
    private static final byte TYPE_DEFLATE = 0x02;

    // ------------------------------------------------------------- digests

    /**
     * All six digest fields exist in every version 3 header, so their presence
     * says nothing. An archive that left them blank recorded nothing, and
     * reporting its tables verified against digests nobody computed is a claim
     * the archive never made.
     */
    @Test
    public void blankDigestsAreNotRecordedDigests() throws IOException {
        final byte[] image = build(new Shape(false, false, false, false));

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.header().formatVersion(), 3);
            Assert.assertFalse(archive.header().extended().hasDigests());
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.UNRECORDED);
            Assert.assertEquals(archive.read("a.txt"), content());
        }
    }

    /** With the digests filled in and the tables intact, everything matches. */
    @Test
    public void recordedDigestsThatMatchReportVerified() throws IOException {
        final byte[] image = build(new Shape(true, false, false, false));

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.header().extended().hasDigests());
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.VERIFIED);
            Assert.assertEquals(archive.read("a.txt"), content());
        }
    }

    /**
     * A damaged table is reported rather than refused. StormLib does the same:
     * the tables may still decode every file, and refusing the archive would
     * throw away data that is actually recoverable.
     */
    @Test
    public void aTableThatDoesNotMatchItsDigestIsReportedNotRefused() throws IOException {
        final byte[] image = build(new Shape(true, false, false, false));
        final int blockTableAt = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getInt(0x14);
        image[blockTableAt] ^= 0x01;

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.MISMATCHED);
        }
    }

    /**
     * The extended tables count too. This library does not read HET or BET, but
     * their digests are recorded, and {@code VERIFIED} promises every recorded
     * digest matched — so an archive whose HET table is the damaged one must not
     * report clean.
     */
    @Test
    public void aDamagedHetTableIsNotReportedAsVerified() throws IOException {
        try (MpqArchive archive = MpqArchive.open(build(new Shape(true, true, false, false)),
            MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.VERIFIED,
                "the HET digest was taken over the region, so it must match");
        }

        final byte[] damaged = build(new Shape(true, true, false, false));
        final int hetAt = (int) ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).getLong(0x3C);
        damaged[hetAt] ^= 0x01;

        try (MpqArchive archive = MpqArchive.open(damaged, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.MISMATCHED);
        }
    }

    /**
     * A digest recorded for bytes the header cannot point at is a failure, not a
     * pass. Nothing was hashed, so the archive cannot be said to agree with its
     * own digests — and since the extended tables are not otherwise read or
     * validated, letting this path succeed would be the only thing between a
     * damaged HET table and a clean report.
     */
    @Test
    public void aDigestRecordedForBytesThatAreNotThereIsAMismatch() throws IOException {
        final byte[] image = build(new Shape(true, false, false, true));

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.header().extended().hasDigests());
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.MISMATCHED,
                "a recorded digest over a region outside the file cannot have matched");
            // Still a readable archive: the classic tables are untouched.
            Assert.assertEquals(archive.read("a.txt"), content());
        }
    }

    // --------------------------------------------------- compressed tables

    /** A compressed hash table is decompressed after being decrypted. */
    @Test
    public void aCompressedHashTableIsRead() throws IOException {
        final byte[] image = build(new Shape(true, false, true, false));

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.header().isHashTableCompressed(),
                "the fixture is meant to have a compressed hash table");
            Assert.assertTrue(archive.header().hashTableStoredSize()
                < (long) archive.header().hashTableEntries() * MpqHeader.HASH_ENTRY_SIZE);
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.VERIFIED);
            Assert.assertEquals(archive.read("a.txt"), content());
        }
    }

    /**
     * The header scan must not rule out a valid header whose compressed hash
     * table sits at the end of the file.
     * <p>
     * Screening candidates on room for the <em>uncompressed</em> table rejects
     * exactly this archive, and then a decoy planted earlier in the file wins
     * the scan — the reverse of what the plausibility check exists for.
     */
    @Test
    public void aDecoyDoesNotBeatAValidCompressedTableHeader() throws IOException {
        final byte[] real = build(new Shape(true, false, true, false));
        final byte[] image = behindADecoy(real);

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.header().headerOffset(), MpqHeader.ALIGNMENT,
                "the decoy must not have won");
            Assert.assertTrue(archive.header().isHashTableCompressed());
            Assert.assertEquals(archive.read("a.txt"), content());
        }
    }

    // ------------------------------------------------------------ fixtures

    /**
     * @param digests        record the MD5 digests.
     * @param extendedTables plant a HET/BET region and record its digest.
     * @param compressedHashTable store the hash table compressed, at the end of
     *                       the file, where requiring room for its uncompressed
     *                       form would run past the end.
     * @param unreachableDigest record a HET digest for a region outside the file.
     */
    private record Shape(boolean digests, boolean extendedTables,
                         boolean compressedHashTable, boolean unreachableDigest) {
    }

    private static byte[] content() {
        return "version three content, long enough to occupy a sector"
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The archive to relay. No list file, because the writer keys that one on
     * its own position and this fixture moves every file.
     */
    private static byte[] source() throws IOException {
        return MpqArchiveWriter
            .create(MpqWriteOptions.defaults()
                .withFormatVersion(1)
                .withPrefix(false)
                .withListfile(false))
            .put("a.txt", content())
            .put("b.txt", "second file".getBytes(StandardCharsets.UTF_8))
            .toByteArray();
    }

    /**
     * Relays a version 1 archive behind a version 3 header.
     * <p>
     * The header grows to 208 bytes, so the file data moves and every position
     * recorded in the header and the block table moves with it. The block table
     * has to be decrypted to be adjusted and re-encrypted, which is why the
     * source must hold no file keyed on its own position. The tables are also
     * reordered so the hash table lands last, which is what lets the compressed
     * variant end the file.
     */
    private static byte[] build(Shape shape) throws IOException {
        final byte[] v1 = source();
        final ByteBuffer in = ByteBuffer.wrap(v1).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(in.getInt(0), MpqHeader.ARCHIVE_SIGNATURE, "header must be at 0");

        final int sourceHeaderSize = in.getInt(0x04);
        final int sourceHashAt = in.getInt(0x10);
        final int sourceBlockAt = in.getInt(0x14);
        final int hashEntries = in.getInt(0x18);
        final int blockEntries = in.getInt(0x1C);
        final int sectorShift = in.getShort(0x0E) & 0xFF;

        final int hashLength = hashEntries * MpqHeader.HASH_ENTRY_SIZE;
        final int blockLength = blockEntries * MpqHeader.BLOCK_ENTRY_SIZE;
        final int dataLength = sourceHashAt - sourceHeaderSize;
        final int shift = V3_HEADER_SIZE - sourceHeaderSize;

        final byte[] blockTable = slice(v1, sourceBlockAt, blockLength);
        shiftFilePositions(blockTable, blockEntries, shift);

        byte[] hashTable = slice(v1, sourceHashAt, hashLength);
        if (shape.compressedHashTable()) {
            hashTable = compressTable(hashTable, tableKey("(hash table)"));
            Assert.assertTrue(hashTable.length < hashLength, "the fixture must actually shrink");
        }

        // A spare region standing in for HET/BET. Its contents are never parsed;
        // only its digest is, which is the whole point.
        final byte[] extended = new byte[64];
        for (int i = 0; i < extended.length; i++) {
            extended[i] = (byte) (i * 7 + 1);
        }

        final int dataAt = V3_HEADER_SIZE;
        final int extendedAt = dataAt + dataLength;
        final int blockAt = extendedAt + (shape.extendedTables() ? extended.length : 0);
        final int hashAt = blockAt + blockLength;
        final byte[] out = new byte[hashAt + hashTable.length];

        System.arraycopy(v1, sourceHeaderSize, out, dataAt, dataLength);
        if (shape.extendedTables()) {
            System.arraycopy(extended, 0, out, extendedAt, extended.length);
        }
        System.arraycopy(blockTable, 0, out, blockAt, blockLength);
        System.arraycopy(hashTable, 0, out, hashAt, hashTable.length);

        final ByteBuffer header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x00, MpqHeader.ARCHIVE_SIGNATURE);
        header.putInt(0x04, V3_HEADER_SIZE);
        header.putInt(0x08, out.length);
        header.putShort(0x0C, (short) 3);
        header.putShort(0x0E, (short) sectorShift);
        header.putInt(0x10, hashAt);
        header.putInt(0x14, blockAt);
        header.putInt(0x18, hashEntries);
        header.putInt(0x1C, blockEntries);
        header.putLong(0x20, 0);
        header.putLong(0x2C, out.length);
        header.putLong(0x44, hashTable.length);
        header.putLong(0x4C, blockLength);
        if (shape.extendedTables()) {
            header.putLong(0x34, extendedAt);
            header.putLong(0x3C, extendedAt);
            header.putLong(0x5C, extended.length);
            header.putLong(0x64, extended.length);
        }
        if (shape.unreachableDigest()) {
            // A HET table the header points well past the end of the file.
            header.putLong(0x3C, out.length + 0x1000L);
            header.putLong(0x5C, 64);
        }

        if (shape.digests()) {
            put(out, MD5_HASH_TABLE, md5(out, hashAt, hashTable.length));
            put(out, MD5_BLOCK_TABLE, md5(out, blockAt, blockLength));
            put(out, MD5_HI_BLOCK_TABLE, new byte[DIGEST_SIZE]);
            if (shape.extendedTables()) {
                put(out, MD5_HET_TABLE, md5(out, extendedAt, extended.length));
                put(out, MD5_BET_TABLE, md5(out, extendedAt, extended.length));
            } else if (shape.unreachableDigest()) {
                // Recorded, and deliberately unverifiable.
                final byte[] digest = new byte[DIGEST_SIZE];
                digest[0] = 0x42;
                put(out, MD5_HET_TABLE, digest);
                put(out, MD5_BET_TABLE, new byte[DIGEST_SIZE]);
            } else {
                put(out, MD5_HET_TABLE, new byte[DIGEST_SIZE]);
                put(out, MD5_BET_TABLE, new byte[DIGEST_SIZE]);
            }
            // The header digest covers the header up to but not including itself.
            put(out, MD5_HEADER, md5(out, 0, MD5_HEADER));
        }
        return out;
    }

    /** Puts a header-shaped decoy in front of a real archive. */
    private static byte[] behindADecoy(byte[] real) {
        final byte[] out = new byte[MpqHeader.ALIGNMENT + real.length];
        final ByteBuffer decoy = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        decoy.putInt(0x00, MpqHeader.ARCHIVE_SIGNATURE);
        decoy.putInt(0x04, 32);
        decoy.putInt(0x10, 0x7FFF_0000);
        decoy.putInt(0x14, 0x7FFF_1000);
        decoy.putInt(0x18, 16);
        System.arraycopy(real, 0, out, MpqHeader.ALIGNMENT, real.length);
        return out;
    }

    /** Compresses a table the way a version 3 archive stores one. */
    private static byte[] compressTable(byte[] stored, int key) {
        final byte[] plain = stored.clone();
        new MPQEncryption(key, true).processSingle(ByteBuffer.wrap(plain));

        final byte[] deflated = CompressionUtil.compress(plain, new RecompressOptions(true));
        final byte[] out = new byte[deflated.length + 1];
        out[0] = TYPE_DEFLATE;
        System.arraycopy(deflated, 0, out, 1, deflated.length);

        // Compressed first, then encrypted, which is why a reader decrypts
        // first and then decompresses.
        new MPQEncryption(key, false).processSingle(ByteBuffer.wrap(out));
        return out;
    }

    /** Adds {@code shift} to every file position in an encrypted block table. */
    private static void shiftFilePositions(byte[] table, int entries, int shift) {
        new MPQEncryption(tableKey("(block table)"), true)
            .processSingle(ByteBuffer.wrap(table));

        final ByteBuffer rows = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < entries; i++) {
            final int at = i * MpqHeader.BLOCK_ENTRY_SIZE;
            rows.putInt(at, rows.getInt(at) + shift);
        }

        new MPQEncryption(tableKey("(block table)"), false)
            .processSingle(ByteBuffer.wrap(table));
    }

    private static byte[] slice(byte[] source, int at, int length) {
        final byte[] out = new byte[length];
        System.arraycopy(source, at, out, 0, length);
        return out;
    }

    private static void put(byte[] image, int at, byte[] digest) {
        System.arraycopy(digest, 0, image, at, DIGEST_SIZE);
    }

    private static byte[] md5(byte[] image, int at, int length) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(image, at, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int tableKey(String name) {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process(name);
        return hasher.getHash();
    }
}
