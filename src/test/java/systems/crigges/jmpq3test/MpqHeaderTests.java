package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq.MpqHeader;
import systems.crigges.jmpq.MpqSource;
import systems.crigges.jmpq3.JMpqException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Header parsing and the {@link MpqSource} read layer (P1-2, P1-3).
 */
public class MpqHeaderTests {

    /**
     * The mapped read layer must release the file as soon as it is closed. The
     * previous {@code MappedByteBuffer} unmapped only at the garbage
     * collector's convenience, which kept the archive locked on Windows and is
     * why the old rebuild staged through a temporary file.
     */
    @Test
    public void closingASourceReleasesTheFileImmediately() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final long size;
        try (MpqSource source = MpqSource.ofFile(mpq)) {
            size = source.size();
            Assert.assertEquals(source.i32(0x200), MpqHeader.ARCHIVE_SIGNATURE);
        }
        Assert.assertEquals(size, Files.size(mpq));

        // Deleting proves the mapping is gone; on Windows this fails outright
        // while a mapping is live.
        Files.delete(mpq);
        Assert.assertFalse(Files.exists(mpq));
    }

    /** Reads outside the archive are data errors, not IndexOutOfBounds. */
    @Test
    public void readsOutsideTheArchiveAreReported() throws IOException {
        try (MpqSource source = MpqSource.ofArray(new byte[16])) {
            Assert.assertEquals(source.size(), 16);
            Assert.assertTrue(source.contains(12, 4));
            Assert.assertFalse(source.contains(13, 4));

            Assert.expectThrows(JMpqException.class, () -> source.i32(13));
            Assert.expectThrows(JMpqException.class, () -> source.i64(9));
            Assert.expectThrows(JMpqException.class, () -> source.u8(16));
            Assert.expectThrows(JMpqException.class, () -> source.bytes(0, 17));
            Assert.expectThrows(JMpqException.class, () -> source.bytes(0, -1));
            Assert.expectThrows(JMpqException.class, () -> source.i32(-1));
        }
    }

    /** Little-endian accessors, including unsigned widening. */
    @Test
    public void accessorsAreLittleEndianAndUnsignedWhereDeclared() throws IOException {
        final byte[] raw = new byte[16];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 0xFFFFFFFF)
            .putShort(4, (short) 0xBEEF)
            .put(6, (byte) 0xFE)
            .putLong(8, -1L);

        try (MpqSource source = MpqSource.ofArray(raw)) {
            Assert.assertEquals(source.i32(0), -1);
            Assert.assertEquals(source.u32(0), 0xFFFFFFFFL);
            Assert.assertEquals(source.u16(4), 0xBEEF);
            Assert.assertEquals(source.u8(6), 0xFE);
            Assert.assertEquals(source.i64(8), -1L);
        }
    }

    /**
     * Every fixture must parse, and the values must match what the independent
     * reference tool reported when the golden manifest was generated.
     */
    @Test
    public void everyFixtureHeaderParses() throws IOException {
        for (Path mpq : TestResources.mpqCopies()) {
            try (MpqSource source = MpqSource.ofFile(mpq)) {
                final MpqHeader header = MpqHeader.parse(source, true);

                Assert.assertEquals(header.formatVersion(), 0, mpq + " forced to v0");
                Assert.assertEquals(header.headerSize(), 32, mpq + " v0 header size");
                Assert.assertTrue(header.hashTableEntries() > 0, mpq + " hash entries");
                Assert.assertTrue(header.sectorSize() >= 512, mpq + " sector size");

                // Every table the header describes must lie inside the file.
                Assert.assertTrue(source.contains(header.hashTableFileOffset(),
                        (long) header.hashTableEntries() * MpqHeader.HASH_ENTRY_SIZE),
                    mpq + " hash table out of range");
                Assert.assertTrue(source.contains(header.blockTableFileOffset(),
                        (long) header.blockTableEntries() * MpqHeader.BLOCK_ENTRY_SIZE),
                    mpq + " block table out of range");

                // The clamp must hold for every archive, not just legacy mode.
                Assert.assertTrue(header.archiveSize() <= source.size() - header.headerOffset(),
                    mpq + " archive size exceeds the file");
            }
        }
    }

    /**
     * P2-5a: a garbage header size must be repaired rather than rejected, which
     * is what makes the protected maps of issue #46 readable. StormLib does the
     * same and flags the archive malformed.
     */
    @Test
    public void garbageHeaderSizeIsRepairedAndFlagged() throws IOException {
        // listfileTooLong.w3x declares a header size of 1347385430.
        for (String name : List.of("listfileTooLong", "spazzledMap")) {
            Path mpq = TestResources.mpqCopy(name);
            try (MpqSource source = MpqSource.ofFile(mpq)) {
                final MpqHeader header = MpqHeader.parse(source, true);
                Assert.assertEquals(header.headerSize(), 32, name);
                Assert.assertTrue(header.malformed(), name + " should be flagged malformed");
            }
        }
    }

    /** A well-formed fixture must not be flagged malformed. */
    @Test
    public void wellFormedHeaderIsNotFlagged() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (MpqSource source = MpqSource.ofFile(mpq)) {
            Assert.assertFalse(MpqHeader.parse(source, true).malformed());
        }
    }

    /** A block table larger than the file is clamped, as StormLib clamps it. */
    @Test
    public void oversizedBlockTableIsClamped() throws IOException {
        Path mpq = TestResources.mpqCopy("spazzledMap");
        try (MpqSource source = MpqSource.ofFile(mpq)) {
            final MpqHeader header = MpqHeader.parse(source, true);
            // The header claims 86529 entries in a 46 KiB file.
            Assert.assertTrue(header.blockTableEntries() < 86529,
                "expected the declared entry count to be clamped, got "
                    + header.blockTableEntries());
            Assert.assertTrue(source.contains(header.blockTableFileOffset(),
                (long) header.blockTableEntries() * MpqHeader.BLOCK_ENTRY_SIZE));
        }
    }

    /** A file with no archive header at all must say so. */
    @Test
    public void missingHeaderIsReported() throws IOException {
        try (MpqSource source = MpqSource.ofArray(new byte[4096])) {
            JMpqException thrown = Assert.expectThrows(JMpqException.class,
                () -> MpqHeader.parse(source, false));
            Assert.assertTrue(thrown.getMessage().contains("No MPQ archive header"), thrown.getMessage());
        }
    }

    /** Sector size derives from the shift, and only its low byte counts. */
    @Test
    public void sectorSizeUsesOnlyTheLowByteOfTheField() throws IOException {
        final byte[] archive = header(builder -> builder.putShort(0x0E, (short) 0x0103));
        try (MpqSource source = MpqSource.ofArray(archive)) {
            final MpqHeader parsed = MpqHeader.parse(source, false);
            Assert.assertEquals(parsed.sectorSizeShift(), 3);
            Assert.assertEquals(parsed.sectorSize(), 4096);
            Assert.assertTrue(parsed.malformed(), "a high byte in wSectorSize means a tampered header");
        }
    }

    /** Builds a minimal but valid version 0 archive image for probing. */
    private static byte[] header(java.util.function.Consumer<ByteBuffer> tweak) {
        final int hashEntries = 4;
        final byte[] image = new byte[32 + hashEntries * 16 + 16];
        final ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x00, MpqHeader.ARCHIVE_SIGNATURE);
        buffer.putInt(0x04, 32);
        buffer.putInt(0x08, image.length);
        buffer.putShort(0x0C, (short) 0);
        buffer.putShort(0x0E, (short) 3);
        buffer.putInt(0x10, 32);
        buffer.putInt(0x14, 32 + hashEntries * 16);
        buffer.putInt(0x18, hashEntries);
        buffer.putInt(0x1C, 1);
        tweak.accept(buffer);
        return image;
    }
}
