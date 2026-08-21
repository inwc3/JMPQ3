package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqAttributes;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.AttributesFile;
import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P2-4: the {@code (attributes)} file, read according to its own bytemask.
 * <p>
 * The pre-2.0 parser read the bytemask and then ignored it, assuming a CRC32
 * array followed by a FILETIME array and nothing else, and subtracted one from
 * the entry count for reasons nobody recorded. These tests pin what the format
 * actually says, so a file carrying MD5 digests or patch bits is read rather
 * than misread.
 */
public class MpqAttributesTests {

    private static final int BLOCKS = 5;

    /** Every array the format defines, at the offsets the format puts them. */
    @Test
    public void everyDeclaredArrayIsRead() throws JMpqException {
        final int flags = MpqAttributes.HAS_CRC32 | MpqAttributes.HAS_FILETIME
            | MpqAttributes.HAS_MD5 | MpqAttributes.HAS_PATCH_BIT;
        final ByteBuffer out = ByteBuffer
            .allocate((int) MpqAttributes.sizeFor(flags, BLOCKS))
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MpqAttributes.VERSION);
        out.putInt(flags);
        for (int i = 0; i < BLOCKS; i++) {
            out.putInt(0x1000 + i);
        }
        for (int i = 0; i < BLOCKS; i++) {
            out.putLong(0x2000L + i);
        }
        for (int i = 0; i < BLOCKS; i++) {
            final byte[] digest = new byte[16];
            digest[0] = (byte) i;
            out.put(digest);
        }
        // Patch bits, most significant bit first: blocks 0 and 3 are patches.
        out.put((byte) 0b1001_0000);

        final MpqAttributes attributes = MpqAttributes.parse(out.array(), BLOCKS);

        Assert.assertEquals(attributes.version(), MpqAttributes.VERSION);
        Assert.assertEquals(attributes.entries(), BLOCKS);
        Assert.assertFalse(attributes.truncated());
        for (int i = 0; i < BLOCKS; i++) {
            Assert.assertEquals(attributes.crc32Of(i), 0x1000 + i, "crc " + i);
            Assert.assertEquals(attributes.fileTimeOf(i), 0x2000L + i, "time " + i);
            Assert.assertEquals(attributes.md5()[i][0], (byte) i, "md5 " + i);
        }
        Assert.assertEquals(attributes.patchBits(), new boolean[]{true, false, false, true, false});
        Assert.assertTrue(attributes.has(MpqAttributes.HAS_MD5));
    }

    /**
     * A CRC32-only file. Under the old fixed layout its entries would have been
     * read as {@code (length - 8) / 12 - 1}, which for five blocks is 0.
     */
    @Test
    public void aCrcOnlyFileIsNotReadAsCrcPlusTimestamps() throws JMpqException {
        final ByteBuffer out = ByteBuffer
            .allocate((int) MpqAttributes.sizeFor(MpqAttributes.HAS_CRC32, BLOCKS))
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MpqAttributes.VERSION);
        out.putInt(MpqAttributes.HAS_CRC32);
        for (int i = 0; i < BLOCKS; i++) {
            out.putInt(0xABC0 + i);
        }

        final MpqAttributes attributes = MpqAttributes.parse(out.array(), BLOCKS);
        Assert.assertEquals(attributes.entries(), BLOCKS);
        Assert.assertEquals(attributes.crc32().length, BLOCKS);
        Assert.assertEquals(attributes.fileTimes().length, 0, "no timestamps were declared");
        Assert.assertEquals(attributes.crc32Of(4), 0xABC4);
        Assert.assertEquals(attributes.fileTimeOf(4), 0, "absent means 0, not out of bounds");
    }

    /**
     * StormLib tolerates an attributes file one entry short, because the tool
     * that wrote it is rarely the tool reading it. That tolerance is the only
     * defensible origin of the old parser's {@code - 1}, which applied it
     * always rather than when the length called for it.
     */
    @Test
    public void oneEntryShortIsAcceptedAndReported() throws JMpqException {
        final int flags = MpqAttributes.HAS_CRC32 | MpqAttributes.HAS_FILETIME;
        final byte[] full = MpqAttributes.build(new int[BLOCKS], new long[BLOCKS]);

        Assert.assertEquals(MpqAttributes.parse(full, BLOCKS).entries(), BLOCKS);
        Assert.assertFalse(MpqAttributes.parse(full, BLOCKS).truncated());

        // The same bytes, for an archive that has one more block than they cover.
        final MpqAttributes shortened = MpqAttributes.parse(full, BLOCKS + 1);
        Assert.assertEquals(shortened.entries(), BLOCKS);
        Assert.assertTrue(shortened.truncated());
        Assert.assertEquals(shortened.flags(), flags);
    }

    /** A length matching no plausible entry count is reported, not guessed at. */
    @Test
    public void anImplausibleLengthIsRejected() {
        final byte[] full = MpqAttributes.build(new int[BLOCKS], new long[BLOCKS]);
        final JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> MpqAttributes.parse(full, BLOCKS + 40));
        Assert.assertTrue(thrown.getMessage().contains("should be"), thrown.getMessage());

        Assert.expectThrows(JMpqException.class, () -> MpqAttributes.parse(new byte[4], 1));
    }

    /**
     * An unknown bit means an array of unknown length, so nothing past the
     * known arrays can be located. The known prefix is still read.
     */
    @Test
    public void unknownFlagsDoNotStopTheKnownArraysBeingRead() throws JMpqException {
        final byte[] file = MpqAttributes.build(new int[]{7, 8}, new long[]{9, 10});
        // Set a bit no version of the format defines.
        file[4] |= 0x40;

        final MpqAttributes attributes = MpqAttributes.parse(file, 2);
        Assert.assertEquals(attributes.crc32Of(0), 7);
        Assert.assertEquals(attributes.fileTimeOf(1), 10);
        Assert.assertTrue(attributes.has(0x40), "the flag is preserved as stored");
        // Re-emitting drops what could not be understood, and says so by
        // emitting only the known bits.
        Assert.assertEquals(MpqAttributes.parse(attributes.toByteArray(), 2).flags(),
            MpqAttributes.HAS_CRC32 | MpqAttributes.HAS_FILETIME);
    }

    /** The default shape round-trips through build and parse unchanged. */
    @Test
    public void theDefaultShapeRoundTrips() throws JMpqException {
        final int[] crc = {1, 2, 3};
        final long[] times = {100, 200, 300};
        final MpqAttributes parsed = MpqAttributes.parse(MpqAttributes.build(crc, times), 3);

        Assert.assertEquals(parsed.crc32(), crc);
        Assert.assertEquals(parsed.fileTimes(), times);
        Assert.assertEquals(parsed.flags(),
            MpqAttributes.HAS_CRC32 | MpqAttributes.HAS_FILETIME);
        Assert.assertEquals(parsed, MpqAttributes.parse(parsed.toByteArray(), 3));
        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqAttributes.build(new int[2], new long[3]));
    }

    /** FILETIME conversion has to survive a round trip at millisecond scale. */
    @Test
    public void fileTimeConversionRoundTrips() {
        final long millis = 1_600_000_000_000L;
        Assert.assertEquals(MpqAttributes.toUnixMillis(MpqAttributes.toFileTime(millis)), millis);
        // 1601-01-01, the FILETIME epoch.
        Assert.assertEquals(MpqAttributes.toFileTime(-11_644_473_600_000L), 0L);
    }

    /**
     * The deprecated parser now derives its count from the bytemask too, so a
     * CRC-plus-timestamp file reports every entry it holds rather than one
     * fewer.
     */
    @Test
    public void theDeprecatedParserNoLongerLosesAnEntry() {
        final int entries = 4;
        final AttributesFile written = new AttributesFile(entries);
        for (int i = 0; i < entries; i++) {
            written.setEntry(i, 0x1000 + i, 0x2000L + i);
        }

        final AttributesFile read = new AttributesFile(written.buildFile());
        Assert.assertEquals(read.entries(), entries, "the unexplained -1 is gone");
        for (int i = 0; i < entries; i++) {
            Assert.assertEquals(read.getCrc32()[i], 0x1000 + i);
            Assert.assertEquals(read.getTimestamps()[i], 0x2000L + i);
        }
    }

    /**
     * A bytemask naming nothing this implementation knows describes no entries.
     * Worth its own test: the length-driven count only terminates because of
     * that, and without the guard the deprecated parser spins forever.
     */
    @Test
    public void aBytemaskNamingNothingKnownDescribesNothing() throws JMpqException {
        final ByteBuffer out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MpqAttributes.VERSION);
        out.putInt(0x40);

        Assert.assertEquals(new AttributesFile(out.array()).entries(), 0);
        // 64 bytes is not 8, so the strict parser reports the mismatch instead.
        Assert.expectThrows(JMpqException.class, () -> MpqAttributes.parse(out.array(), 3));
    }
}
