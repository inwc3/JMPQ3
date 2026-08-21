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
     * StormLib sizes the patch-bit array as {@code (n + 6) / 8} but loads it as
     * {@code (n + 7) / 8}, so for a block count congruent to 1 modulo 8 the file
     * it writes is one byte short of its own bit count -- a one-block archive is
     * allotted zero bytes for one bit.
     * <p>
     * Both lengths therefore occur, and neither may be read past. Reading the
     * short form used to throw {@link ArrayIndexOutOfBoundsException} on an
     * otherwise length-valid file.
     */
    @Test
    public void bothPatchBitLengthsAreAcceptedAndNeitherIsReadPast() throws JMpqException {
        // One block: StormLib allots (1 + 6) / 8 = 0 bytes for the bit.
        final byte[] oneBlockShort = patchBitFile(1, 0);
        Assert.assertEquals(oneBlockShort.length, 8);
        final MpqAttributes one = MpqAttributes.parse(oneBlockShort, 1);
        Assert.assertEquals(one.entries(), 1);
        Assert.assertEquals(one.patchBits(), new boolean[]{false},
            "the bit was never stored, so it is not set");

        // Nine blocks: (9 + 6) / 8 = 1 byte, one short of the nine bits.
        final byte[] nineShort = patchBitFile(9, 1);
        nineShort[8] = (byte) 0b1000_0001;
        final MpqAttributes nine = MpqAttributes.parse(nineShort, 9);
        Assert.assertEquals(nine.entries(), 9);
        Assert.assertTrue(nine.patchBits()[0]);
        Assert.assertTrue(nine.patchBits()[7]);
        Assert.assertFalse(nine.patchBits()[8], "the ninth bit had nowhere to live");

        // The same nine blocks written to the length that actually holds them.
        final byte[] nineFull = patchBitFile(9, 2);
        nineFull[8] = (byte) 0b1000_0001;
        nineFull[9] = (byte) 0b1000_0000;
        final MpqAttributes read = MpqAttributes.parse(nineFull, 9);
        Assert.assertEquals(read.entries(), 9);
        Assert.assertTrue(read.patchBits()[8], "now it does");
    }

    /**
     * What this implementation emits is the length that holds every bit, so a
     * write can never leave the buffer, and it must parse back unchanged.
     */
    @Test
    public void emittedPatchBitsRoundTripAtEveryAwkwardCount() throws JMpqException {
        for (int entries : new int[]{1, 7, 8, 9, 16, 17}) {
            final boolean[] bits = new boolean[entries];
            bits[entries - 1] = true;
            bits[0] = true;
            final MpqAttributes attributes = new MpqAttributes(MpqAttributes.VERSION,
                MpqAttributes.HAS_PATCH_BIT, new int[0], new long[0], new byte[0][],
                bits, false);

            final MpqAttributes read = MpqAttributes.parse(attributes.toByteArray(), entries);
            Assert.assertEquals(read.entries(), entries, "entries for " + entries);
            Assert.assertEquals(read.patchBits(), bits, "bits for " + entries);
        }
    }

    /** Patch bits sit after the arrays that precede them, not at a fixed offset. */
    @Test
    public void patchBitsAreReadAfterTheArraysBeforeThem() throws JMpqException {
        final int entries = 9;
        final int flags = MpqAttributes.HAS_CRC32 | MpqAttributes.HAS_PATCH_BIT;
        final ByteBuffer out = ByteBuffer
            .allocate((int) MpqAttributes.sizeFor(flags, entries))
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MpqAttributes.VERSION);
        out.putInt(flags);
        for (int i = 0; i < entries; i++) {
            out.putInt(0x500 + i);
        }
        out.put((byte) 0b0100_0000);

        final MpqAttributes attributes = MpqAttributes.parse(out.array(), entries);
        Assert.assertEquals(attributes.crc32Of(8), 0x508);
        Assert.assertFalse(attributes.patchBits()[0]);
        Assert.assertTrue(attributes.patchBits()[1]);
    }

    /** A patch-bit-only attributes file of the given length in bit bytes. */
    private static byte[] patchBitFile(int entries, int patchBytes) {
        final ByteBuffer out = ByteBuffer.allocate(8 + patchBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MpqAttributes.VERSION);
        out.putInt(MpqAttributes.HAS_PATCH_BIT);
        return out.array();
    }

    /**
     * A version other than 100 means the body is not laid out the way this code
     * reads it.
     * <p>
     * 100 is the only version the format has ever had, so a different one is
     * either a future format or corruption. Parsing it anyway turns a
     * same-length file into plausible-looking checksums and timestamps that
     * describe nothing, which is worse than reporting it unreadable — the
     * archive still opens either way, since attributes are advisory.
     */
    @Test
    public void anUnknownVersionIsRejectedRatherThanReinterpreted() {
        final byte[] file = MpqAttributes.build(new int[]{1, 2}, new long[]{3, 4});
        file[0] = (byte) 200;

        final JMpqException thrown = Assert.expectThrows(JMpqException.class,
            () -> MpqAttributes.parse(file, 2));
        Assert.assertTrue(thrown.getMessage().contains("version 200"), thrown.getMessage());

        // Version 0, which is what a zeroed or truncated file looks like.
        final byte[] zeroed = MpqAttributes.build(new int[]{1, 2}, new long[]{3, 4});
        zeroed[0] = 0;
        Assert.expectThrows(JMpqException.class, () -> MpqAttributes.parse(zeroed, 2));
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
