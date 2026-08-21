package org.inwc3.jmpq;

/**
 * A user data header, the {@code MPQ\x1B} block that can precede an archive.
 *
 * <h2>Layout</h2>
 * <pre>
 * 0x00 u32  signature 'MPQ\x1B'
 * 0x04 u32  size of the user data area
 * 0x08 u32  offset of the archive header, relative to this header
 * 0x0C u32  size of this user data header
 * </pre>
 *
 * <h2>What it is for</h2>
 * Blizzard uses it to staple metadata in front of an archive — a StarCraft II
 * map keeps its map info here — so the archive proper starts further into the
 * file. Readers that honour it find the header via {@link #headerOffset()};
 * Warcraft III ignores the block entirely, which is why
 * {@link MpqOpenOptions#forceV0()} skips it.
 * <p>
 * The pre-2.0 code detected the signature and then discarded everything but the
 * redirect, with a TODO where the model should have been. Keeping it means a
 * caller can read the user data area, and — more importantly — a rebuild can
 * preserve it instead of silently dropping a map's metadata.
 *
 * @param offset         where this header sits in the file.
 * @param userDataSize   declared size of the user data area that follows.
 * @param headerOffset   archive header offset, relative to {@link #offset}.
 * @param headerSize     declared size of this user data header.
 */
public record MpqUserData(
    long offset,
    int userDataSize,
    int headerOffset,
    int headerSize) {

    /** Size of the fixed part of a user data header. */
    public static final int SIZE = 16;

    /**
     * Reads a user data header.
     *
     * @param source the archive bytes.
     * @param offset where the {@code MPQ\x1B} signature was found.
     * @return the parsed header, or {@code null} if the bytes do not hold one.
     */
    static MpqUserData readAt(MpqSource source, long offset) {
        try {
            if (!source.contains(offset, SIZE)
                || source.i32(offset) != MpqHeader.USER_DATA_SIGNATURE) {
                return null;
            }
            return new MpqUserData(offset,
                source.i32(offset + 0x04),
                source.i32(offset + 0x08),
                source.i32(offset + 0x0C));
        } catch (systems.crigges.jmpq3.JMpqException unreadable) {
            return null;
        }
    }

    /**
     * @return absolute file offset the archive header should be at.
     */
    public long archiveHeaderOffset() {
        return offset + Integer.toUnsignedLong(headerOffset);
    }

    /**
     * The user data payload, which is whatever the producing tool put there.
     *
     * @param source the archive bytes.
     * @return the payload, truncated to what the file actually holds.
     * @throws systems.crigges.jmpq3.JMpqException if the bytes cannot be read.
     */
    public byte[] payload(MpqSource source) throws systems.crigges.jmpq3.JMpqException {
        final long start = offset + SIZE;
        final long declared = Integer.toUnsignedLong(userDataSize);
        // The declared size is not trustworthy: it is a plain u32 written by
        // another tool, so clamp it to the file rather than letting it drive
        // the allocation.
        final long available = Math.max(0, source.size() - start);
        return source.bytes(start, (int) Math.min(declared, available));
    }
}
