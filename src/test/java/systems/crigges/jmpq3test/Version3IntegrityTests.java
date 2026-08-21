package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqHeader;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The version 3 MD5 digests, and what {@link MpqArchive.Integrity} may claim.
 * <p>
 * The library cannot write version 3, so these fixtures are built by rewriting a
 * version 1 archive's header as a 208-byte one and shifting everything after it.
 * That is still a synthetic fixture rather than a StormLib-generated archive —
 * see P2-2 in {@code AUDIT.md} — but it does exercise the real parse and digest
 * paths rather than only their inputs.
 */
public class Version3IntegrityTests {

    /** A version 3 header is this much longer than a version 0 one. */
    private static final int SHIFT = 208 - 32;

    private static final int DIGEST_SIZE = 16;

    /** Offsets of the six digests within a version 3 header. */
    private static final int MD5_BLOCK_TABLE = 0x70;
    private static final int MD5_HASH_TABLE = 0x80;
    private static final int MD5_HI_BLOCK_TABLE = 0x90;
    private static final int MD5_BET_TABLE = 0xA0;
    private static final int MD5_HET_TABLE = 0xB0;
    private static final int MD5_HEADER = 0xC0;

    /**
     * All six digest fields exist in every version 3 header, so their presence
     * says nothing. An archive that left them blank has not recorded anything,
     * and reporting its tables as verified against digests nobody computed is a
     * claim the archive never made.
     */
    @Test
    public void blankDigestsAreNotRecordedDigests() throws IOException {
        final byte[] image = version3(source(), false, false);

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
        final byte[] image = version3(source(), true, false);

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
        final byte[] image = version3(source(), true, false);

        // Flip a byte of the hash table, leaving its digest claiming otherwise.
        final ByteBuffer header = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        final int hashTableAt = header.getInt(0x10);
        image[hashTableAt] ^= 0x01;

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.MISMATCHED);
        }
    }

    /**
     * The extended tables count too. This library does not read HET or BET, but
     * their digests are still recorded, and {@code VERIFIED} promises that every
     * recorded digest matched — so an archive whose HET table is the damaged one
     * must not report clean.
     */
    @Test
    public void aDamagedHetTableIsNotReportedAsVerified() throws IOException {
        final byte[] intact = version3(source(), true, true);
        try (MpqArchive archive = MpqArchive.open(intact, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.VERIFIED,
                "the HET digest was computed over the region, so it must match");
        }

        final byte[] damaged = version3(source(), true, true);
        final ByteBuffer header = ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN);
        final int hetAt = (int) header.getLong(0x3C);
        damaged[hetAt] ^= 0x01;

        try (MpqArchive archive = MpqArchive.open(damaged, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.integrity(), MpqArchive.Integrity.MISMATCHED,
                "a recorded HET digest that does not match is still a mismatch");
        }
    }

    // ------------------------------------------------------------- fixtures

    private static byte[] content() {
        return "version three content".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The archive to rewrite. No list file, because the writer encrypts that one
     * with a position-adjusted key and this fixture moves every file.
     */
    private static byte[] source() throws IOException {
        return MpqArchiveWriter
            .create(MpqWriteOptions.defaults()
                .withFormatVersion(1)
                .withPrefix(false)
                .withListfile(false))
            .put("a.txt", content())
            .toByteArray();
    }

    /**
     * Rewrites a version 1 archive as a version 3 one.
     * <p>
     * The header grows from 44 to 208 bytes at the same offset, so everything
     * after it moves by {@link #SHIFT} and every position recorded in the header
     * and the block table moves with it. The block table has to be decrypted to
     * be adjusted and then re-encrypted, which is why the source archive must
     * have no files keyed on their own position.
     *
     * @param v1         the source archive, header at offset 0.
     * @param digests    whether to record the MD5 digests.
     * @param extendedTables whether to plant a HET/BET region and record its
     *                   digest, to check that those are covered too.
     * @return a version 3 archive.
     */
    private static byte[] version3(byte[] v1, boolean digests, boolean extendedTables) {
        final ByteBuffer in = ByteBuffer.wrap(v1).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(in.getInt(0), MpqHeader.ARCHIVE_SIGNATURE, "header must be at 0");

        final long hashPosition = Integer.toUnsignedLong(in.getInt(0x10)) + SHIFT;
        final long blockPosition = Integer.toUnsignedLong(in.getInt(0x14)) + SHIFT;
        final int hashEntries = in.getInt(0x18);
        final int blockEntries = in.getInt(0x1C);
        final int sectorShift = in.getShort(0x0E) & 0xFF;

        // A spare region standing in for HET/BET. Its contents are never parsed;
        // only its digest is, which is the whole point.
        final byte[] extended = new byte[64];
        for (int i = 0; i < extended.length; i++) {
            extended[i] = (byte) (i * 7 + 1);
        }

        final int bodyLength = v1.length - 32;
        final int extendedAt = 208 + bodyLength;
        final byte[] out = new byte[extendedAt + (extendedTables ? extended.length : 0)];
        System.arraycopy(v1, 32, out, 208, bodyLength);
        if (extendedTables) {
            System.arraycopy(extended, 0, out, extendedAt, extended.length);
        }

        final ByteBuffer header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x00, MpqHeader.ARCHIVE_SIGNATURE);
        header.putInt(0x04, 208);
        header.putInt(0x08, out.length);
        header.putShort(0x0C, (short) 3);
        header.putShort(0x0E, (short) sectorShift);
        header.putInt(0x10, (int) hashPosition);
        header.putInt(0x14, (int) blockPosition);
        header.putInt(0x18, hashEntries);
        header.putInt(0x1C, blockEntries);
        header.putLong(0x20, 0);
        header.putLong(0x2C, out.length);
        if (extendedTables) {
            // BET at 0x34, HET at 0x3C, both pointing at the spare region.
            header.putLong(0x34, extendedAt);
            header.putLong(0x3C, extendedAt);
            header.putLong(0x5C, extended.length);
            header.putLong(0x64, extended.length);
        }
        // Stored lengths equal to the plain lengths: not compressed.
        header.putLong(0x44, (long) hashEntries * MpqHeader.HASH_ENTRY_SIZE);
        header.putLong(0x4C, (long) blockEntries * MpqHeader.BLOCK_ENTRY_SIZE);

        shiftBlockTable(out, (int) blockPosition, blockEntries);

        if (digests) {
            recordDigests(out, (int) hashPosition, hashEntries, (int) blockPosition, blockEntries,
                extendedTables ? extendedAt : -1, extended.length);
        }
        return out;
    }

    /** Adds {@link #SHIFT} to every file position in the encrypted block table. */
    private static void shiftBlockTable(byte[] image, int at, int entries) {
        final int length = entries * MpqHeader.BLOCK_ENTRY_SIZE;
        final byte[] table = new byte[length];
        System.arraycopy(image, at, table, 0, length);

        new MPQEncryption(tableKey("(block table)"), true)
            .processSingle(ByteBuffer.wrap(table));

        final ByteBuffer rows = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < entries; i++) {
            final int position = rows.getInt(i * MpqHeader.BLOCK_ENTRY_SIZE);
            rows.putInt(i * MpqHeader.BLOCK_ENTRY_SIZE, position + SHIFT);
        }

        new MPQEncryption(tableKey("(block table)"), false)
            .processSingle(ByteBuffer.wrap(table));
        System.arraycopy(table, 0, image, at, length);
    }

    private static void recordDigests(byte[] image, int hashAt, int hashEntries,
                                      int blockAt, int blockEntries,
                                      int extendedAt, int extendedLength) {
        put(image, MD5_HASH_TABLE, md5(image, hashAt, hashEntries * MpqHeader.HASH_ENTRY_SIZE));
        put(image, MD5_BLOCK_TABLE, md5(image, blockAt, blockEntries * MpqHeader.BLOCK_ENTRY_SIZE));
        put(image, MD5_HI_BLOCK_TABLE, new byte[DIGEST_SIZE]);
        if (extendedAt >= 0) {
            put(image, MD5_HET_TABLE, md5(image, extendedAt, extendedLength));
            put(image, MD5_BET_TABLE, md5(image, extendedAt, extendedLength));
        } else {
            put(image, MD5_HET_TABLE, new byte[DIGEST_SIZE]);
            put(image, MD5_BET_TABLE, new byte[DIGEST_SIZE]);
        }
        // The header digest covers the header up to but not including itself.
        put(image, MD5_HEADER, md5(image, 0, MD5_HEADER));
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
