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
 * Blizzard uses it to staple metadata in front of an archive - a StarCraft II
 * map keeps its map info here - so the archive proper starts further into the
 * file. Readers that honour it find the header via {@link #headerOffset()};
 * Warcraft III ignores the block entirely, which is why
 * {@link MpqOpenOptions#forceV0()} skips it.
 * <p>
 * The pre-2.0 code detected the signature and then discarded everything but the
 * redirect, with a TODO where the model should have been. Keeping it means a
 * caller can read the user data area, and - more importantly - a rebuild can
 * preserve it instead of silently dropping a map's metadata.
 *
 * <h2>Which field is the payload length</h2>
 * Neither of the size fields, as it turns out. StormLib documents
 * {@code cbUserDataSize} as the <em>maximum</em> size of the user data - a
 * capacity, not a length - and its comment on {@code cbUserDataHeader} is openly
 * unsure: "Appears to be size of user data header (Starcraft II maps)". What
 * StormLib actually hands a caller asking for the user data is the span between
 * the two headers:
 * <pre>
 * // SFileGetFileInfo.cpp, case SFileMpqUserData
 * ha->UserDataPos + sizeof(TMPQUserData),
 * ha->pUserData->dwHeaderOffs - sizeof(TMPQUserData)
 * </pre>
 * So the redirect offset defines the payload, and both size fields are carried
 * here for inspection without being trusted to bound anything.
 *
 * @param offset         where this header sits in the file.
 * @param userDataSize   {@code cbUserDataSize}: the capacity of the user data
 *                       area, which is advisory and may exceed what is there.
 * @param headerOffset   {@code dwHeaderOffs}: archive header offset, relative to
 *                       {@link #offset}. This is what bounds the payload.
 * @param userDataHeaderSize {@code cbUserDataHeader}, whose meaning StormLib
 *                       itself hedges on. Recorded, not relied upon.
 */
public record MpqUserData(
    long offset,
    int userDataSize,
    int headerOffset,
    int userDataHeaderSize) {

    /** Size of the fixed part of a user data header. */
    public static final int SIZE = 16;

    /**
     * Largest payload {@link #payload} will return. Both bounds it clamps to are
     * 64-bit, so without this the narrowing to an array length could wrap.
     */
    private static final long MAX_PAYLOAD = Integer.MAX_VALUE - 8L;

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
     * @return everything between this header and the archive header, truncated
     *         to what the file actually holds.
     * @throws systems.crigges.jmpq3.JMpqException if the bytes cannot be read.
     */
    public byte[] payload(MpqSource source) throws systems.crigges.jmpq3.JMpqException {
        final long start = offset + SIZE;

        // The span between the two headers, which is what StormLib returns, and
        // then clamped to the file because the redirect offset is a plain u32
        // written by another tool. Neither size field takes part: one is a
        // capacity and the other has no agreed meaning, so an archive reserving
        // more area than it filled, or declaring less than it holds, is read the
        // same way StormLib reads it.
        final long limit = Math.min(source.size(), Math.max(start, archiveHeaderOffset()));
        final long length = Math.max(0, limit - start);

        if (length > MAX_PAYLOAD) {
            throw new systems.crigges.jmpq3.JMpqException("A user data header at " + offset
                + " spans " + length + " bytes before the archive, more than can be returned"
                + " in one array.");
        }
        return source.bytes(start, (int) length);
    }
}
