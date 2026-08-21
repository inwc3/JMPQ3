package org.inwc3.jmpq;

import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The optional {@code (attributes)} file: per-block CRC32, timestamp and MD5.
 *
 * <h2>Layout</h2>
 * <pre>
 * 0x00 u32  version, always 100
 * 0x04 u32  bytemask of which arrays follow
 * 0x08      u32   crc32     [blockCount]   when CRC32 is set
 *           u64   fileTime  [blockCount]   when FILETIME is set
 *           u8x16 md5       [blockCount]   when MD5 is set
 *           bits  patch     [blockCount]   when PATCH_BIT is set
 * </pre>
 * Every array is indexed by <em>block table index</em>, not by file name, and
 * holds one entry per block table row — including the {@code (attributes)} row
 * itself, whose own checksum cannot be computed and is left zero.
 *
 * <h2>Why this replaces the old parser</h2>
 * The pre-2.0 {@code AttributesFile} assumed the CRC32 and FILETIME arrays were
 * both present and no others, deriving the entry count as
 * {@code (length - 8) / 12 - 1}. That is wrong in three ways: it ignores the
 * bytemask it just read, it misreads any archive carrying MD5s, and the
 * {@code - 1} hardcodes one of the several lengths StormLib tolerates rather
 * than working out which one this file actually is.
 * <p>
 * StormLib does accept a short attributes file: it is typically written by a
 * different tool than the one reading it, and being one entry shy is common
 * enough that refusing it would reject working archives. So the count is
 * resolved by matching the declared bytemask against the plausible lengths, and
 * a file matching none is reported rather than silently misparsed.
 *
 * @param version   declared format version; 100 is the only known value.
 * @param flags     the bytemask, as stored.
 * @param crc32     zlib CRC32 per block, empty when not present.
 * @param fileTimes Windows FILETIME per block, empty when not present.
 * @param md5       16 bytes per block, empty when not present.
 * @param patchBits one flag per block, empty when not present.
 * @param truncated whether the file held one fewer entry than the block table.
 */
public record MpqAttributes(
    int version,
    int flags,
    int[] crc32,
    long[] fileTimes,
    byte[][] md5,
    boolean[] patchBits,
    boolean truncated) {

    /** The name under which an archive carries its attributes. */
    public static final String NAME = "(attributes)";

    /** The only format version StormLib writes or accepts. */
    public static final int VERSION = 100;

    /** A zlib CRC32 per block follows. */
    public static final int HAS_CRC32 = 0x01;

    /** A Windows FILETIME per block follows. */
    public static final int HAS_FILETIME = 0x02;

    /** An MD5 digest per block follows. */
    public static final int HAS_MD5 = 0x04;

    /** A patch-marker bit per block follows. */
    public static final int HAS_PATCH_BIT = 0x08;

    /** Every bit this implementation understands. */
    public static final int KNOWN_FLAGS = HAS_CRC32 | HAS_FILETIME | HAS_MD5 | HAS_PATCH_BIT;

    /** Size of the fixed header. */
    private static final int HEADER_SIZE = 8;

    /** Difference between the FILETIME and Unix epochs, in milliseconds. */
    private static final long EPOCH_OFFSET_MILLIS = 11_644_473_600_000L;

    /** FILETIME ticks per millisecond. */
    private static final long TICKS_PER_MILLI = 10_000L;

    /**
     * Converts a Unix millisecond timestamp to a Windows FILETIME.
     *
     * @param unixMillis milliseconds since 1970-01-01 UTC.
     * @return 100-nanosecond intervals since 1601-01-01 UTC.
     */
    public static long toFileTime(long unixMillis) {
        return (unixMillis + EPOCH_OFFSET_MILLIS) * TICKS_PER_MILLI;
    }

    /**
     * Converts a Windows FILETIME back to Unix milliseconds.
     *
     * @param fileTime 100-nanosecond intervals since 1601-01-01 UTC.
     * @return milliseconds since 1970-01-01 UTC.
     */
    public static long toUnixMillis(long fileTime) {
        return fileTime / TICKS_PER_MILLI - EPOCH_OFFSET_MILLIS;
    }

    /**
     * The file length StormLib would write for this shape.
     * <p>
     * Exact for every combination except {@link #HAS_PATCH_BIT}, where the
     * format has no single answer: StormLib sizes that array one way and reads
     * it another, so a patch-bit file may legally be this length or one byte
     * longer. {@link #parse} accepts both, and {@link #toByteArray} emits the
     * longer one, because it is the only one that holds every bit.
     *
     * @param flags   the bytemask.
     * @param entries number of blocks described.
     * @return the length StormLib writes.
     */
    public static long sizeFor(int flags, int entries) {
        return sizeFor(flags, entries, patchBitBytesStormLibWrites(entries));
    }

    private static long sizeFor(int flags, int entries, long patchBytes) {
        long size = HEADER_SIZE;
        if ((flags & HAS_CRC32) != 0) {
            size += 4L * entries;
        }
        if ((flags & HAS_FILETIME) != 0) {
            size += 8L * entries;
        }
        if ((flags & HAS_MD5) != 0) {
            size += 16L * entries;
        }
        if ((flags & HAS_PATCH_BIT) != 0) {
            size += patchBytes;
        }
        return size;
    }

    /**
     * Patch-bit bytes as StormLib counts them in
     * {@code GetSizeOfAttributesFile}: {@code (n + 6) / 8}.
     * <p>
     * That is one byte short of holding {@code n} bits whenever {@code n} is
     * congruent to 1 modulo 8 -- a one-block archive is allotted zero bytes for
     * one bit. StormLib is inconsistent with itself here: its loader sizes the
     * same array as {@code (n + 7) / 8}. Both lengths therefore occur, so
     * parsing accepts either and nothing indexes past what a file holds.
     *
     * @param entries number of blocks described.
     * @return the byte count StormLib writes.
     */
    private static long patchBitBytesStormLibWrites(int entries) {
        return (entries + 6L) / 8;
    }

    /**
     * @param entries number of blocks described.
     * @return bytes actually needed to hold that many bits, which is what this
     *         implementation emits so a write can never leave the buffer.
     */
    private static long patchBitBytesNeeded(int entries) {
        return (entries + 7L) / 8;
    }

    /**
     * {@link #sizeFor} narrowed for an allocation.
     * <p>
     * The entry count comes from a block table, so a large enough archive -- or
     * a caller asking for a great many spare block slots -- can describe more
     * attributes than fit in an array. Casting blind would wrap to a negative
     * length and fail with a message about the wrong thing.
     *
     * @param flags   the bytemask.
     * @param entries number of blocks described.
     * @return the size as an {@code int}.
     */
    private static int inMemorySize(int flags, int entries) {
        return inMemorySize(flags, entries, patchBitBytesStormLibWrites(entries));
    }

    private static int inMemorySize(int flags, int entries, long patchBytes) {
        final long size = sizeFor(flags, entries, patchBytes);
        if (size > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Attributes for " + entries + " blocks would need "
                + size + " bytes, more than can be held in memory.");
        }
        return (int) size;
    }

    /**
     * Works out how many blocks a file of this length describes.
     * <p>
     * Four lengths are legal for one archive: the block count or one fewer --
     * StormLib tolerates a short file, because the tool writing it is rarely the
     * tool reading it -- each with either patch-bit length, since StormLib
     * writes one and reads the other. A length matching none is reported rather
     * than guessed at.
     *
     * @param usable     the bytemask, restricted to arrays we understand.
     * @param blockCount block table rows the archive has.
     * @param length     the file length.
     * @return the entry count that length implies.
     * @throws JMpqException if no candidate matches.
     */
    private static int resolveEntryCount(int usable, int blockCount, int length)
        throws JMpqException {
        final int fewest = Math.max(0, blockCount - 1);
        for (int entries = blockCount; entries >= fewest; entries--) {
            if (sizeFor(usable, entries, patchBitBytesStormLibWrites(entries)) == length
                || sizeFor(usable, entries, patchBitBytesNeeded(entries)) == length) {
                return entries;
            }
        }
        throw new JMpqException("An attributes file with flags 0x"
            + Integer.toHexString(usable) + " for " + blockCount + " blocks should be "
            + sizeFor(usable, blockCount) + " bytes, but is " + length + ".");
    }

    /**
     * Parses an attributes file.
     *
     * @param data       the file content, already decoded.
     * @param blockCount how many block table rows the archive has.
     * @return the parsed attributes.
     * @throws JMpqException if the content cannot be read as attributes for an
     *                       archive of this size.
     */
    public static MpqAttributes parse(byte[] data, int blockCount) throws JMpqException {
        if (data.length < HEADER_SIZE) {
            throw new JMpqException("An attributes file needs at least " + HEADER_SIZE
                + " bytes, got " + data.length + ".");
        }
        final ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        final int version = in.getInt();
        final int flags = in.getInt();

        // An unknown bit means an array of unknown length, so nothing after the
        // arrays we do understand can be located. Reading the known prefix and
        // ignoring the rest is what StormLib does.
        final int usable = flags & KNOWN_FLAGS;

        final int entries = resolveEntryCount(usable, blockCount, data.length);
        final boolean truncated = entries != blockCount;

        final int[] crc32 = (usable & HAS_CRC32) != 0 ? new int[entries] : new int[0];
        for (int i = 0; i < crc32.length; i++) {
            crc32[i] = in.getInt();
        }
        final long[] fileTimes = (usable & HAS_FILETIME) != 0 ? new long[entries] : new long[0];
        for (int i = 0; i < fileTimes.length; i++) {
            fileTimes[i] = in.getLong();
        }
        final byte[][] md5 = (usable & HAS_MD5) != 0 ? new byte[entries][] : new byte[0][];
        for (int i = 0; i < md5.length; i++) {
            md5[i] = new byte[16];
            in.get(md5[i]);
        }
        final boolean[] patchBits =
            (usable & HAS_PATCH_BIT) != 0 ? new boolean[entries] : new boolean[0];
        final int bitsAt = in.position();
        for (int i = 0; i < patchBits.length; i++) {
            final int at = bitsAt + (i >>> 3);
            // A file written to StormLib own size formula can be a byte short of
            // its own bit count. The entries it does not reach are left unmarked,
            // which beats refusing the file or reading past its end.
            patchBits[i] = at < data.length && (data[at] & (0x80 >>> (i & 7))) != 0;
        }

        return new MpqAttributes(version, flags, crc32, fileTimes, md5, patchBits, truncated);
    }

    /**
     * Builds a CRC32-plus-timestamp attributes file, which is the shape
     * StormLib writes by default.
     *
     * @param crc32     one zlib CRC32 per block; 0 where unknown.
     * @param fileTimes one Windows FILETIME per block.
     * @return the file content.
     */
    public static byte[] build(int[] crc32, long[] fileTimes) {
        if (crc32.length != fileTimes.length) {
            throw new IllegalArgumentException("Got " + crc32.length + " checksums but "
                + fileTimes.length + " timestamps.");
        }
        final int flags = HAS_CRC32 | HAS_FILETIME;
        final ByteBuffer out = ByteBuffer
            .allocate(inMemorySize(flags, crc32.length))
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(VERSION);
        out.putInt(flags);
        for (int value : crc32) {
            out.putInt(value);
        }
        for (long value : fileTimes) {
            out.putLong(value);
        }
        return out.array();
    }

    /**
     * @return this attributes file serialised. Only the arrays this
     *         implementation understands are emitted, so a file that carried
     *         unknown ones does not round-trip byte for byte.
     */
    public byte[] toByteArray() {
        final int emitted = flags & KNOWN_FLAGS;
        // Sized to hold every bit rather than to StormLib short formula, so a
        // block count congruent to 1 modulo 8 cannot write past the buffer.
        final ByteBuffer out = ByteBuffer
            .allocate(inMemorySize(emitted, entries(), patchBitBytesNeeded(entries())))
            .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(version);
        out.putInt(emitted);
        for (int value : crc32) {
            out.putInt(value);
        }
        for (long value : fileTimes) {
            out.putLong(value);
        }
        for (byte[] digest : md5) {
            out.put(digest);
        }
        if (patchBits.length > 0) {
            final byte[] bits = new byte[(int) patchBitBytesNeeded(patchBits.length)];
            for (int i = 0; i < patchBits.length; i++) {
                if (patchBits[i]) {
                    bits[i >>> 3] |= (byte) (0x80 >>> (i & 7));
                }
            }
            out.put(bits);
        }
        return out.array();
    }

    /**
     * @return how many blocks this file describes.
     */
    public int entries() {
        if (crc32.length > 0) {
            return crc32.length;
        }
        if (fileTimes.length > 0) {
            return fileTimes.length;
        }
        if (md5.length > 0) {
            return md5.length;
        }
        return patchBits.length;
    }

    /**
     * The recorded checksum for a block.
     * <p>
     * Zero and {@code 0xFFFFFFFF} both mean "not recorded" — StormLib skips
     * verification for either — so a caller comparing checksums must treat them
     * as absent rather than as a mismatch.
     *
     * @param blockIndex block table index.
     * @return the CRC32, or 0 when not recorded.
     */
    public int crc32Of(int blockIndex) {
        return blockIndex >= 0 && blockIndex < crc32.length ? crc32[blockIndex] : 0;
    }

    /**
     * @param blockIndex block table index.
     * @return the timestamp, or 0 when not recorded.
     */
    public long fileTimeOf(int blockIndex) {
        return blockIndex >= 0 && blockIndex < fileTimes.length ? fileTimes[blockIndex] : 0;
    }

    /**
     * @param flag one of the {@code HAS_} constants.
     * @return whether the file declares that array.
     */
    public boolean has(int flag) {
        return (flags & flag) == flag;
    }

    @Override
    public String toString() {
        return "MpqAttributes[version=" + version + ", flags=0x" + Integer.toHexString(flags)
            + ", entries=" + entries() + (truncated ? ", truncated" : "") + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MpqAttributes that)) {
            return false;
        }
        return version == that.version && flags == that.flags && truncated == that.truncated
            && Arrays.equals(crc32, that.crc32)
            && Arrays.equals(fileTimes, that.fileTimes)
            && Arrays.deepEquals(md5, that.md5)
            && Arrays.equals(patchBits, that.patchBits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(crc32) * 31 + flags;
    }
}
