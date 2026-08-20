package org.inwc3.jmpq;

import systems.crigges.jmpq3.JMpqException;

/**
 * An MPQ archive header, parsed into an immutable model.
 *
 * <h2>Field layout</h2>
 * Offsets are relative to the header's own start, per StormLib's
 * {@code TMPQHeader}:
 * <pre>
 * 0x00 u32  signature 'MPQ\x1A'
 * 0x04 u32  header size
 * 0x08 u32  archive size            (deprecated from version 1 onwards)
 * 0x0C u16  format version
 * 0x0E u16  sector size shift       (only the low byte is used)
 * 0x10 u32  hash table position
 * 0x14 u32  block table position
 * 0x18 u32  hash table entries
 * 0x1C u32  block table entries     -- end of a version 0 header, 32 bytes
 * 0x20 u64  hi-block table position
 * 0x28 u16  hash table position, high 16 bits
 * 0x2A u16  block table position, high 16 bits  -- end of version 1, 44 bytes
 * 0x2C u64  archive size, 64-bit
 * 0x34 u64  BET table position
 * 0x3C u64  HET table position      -- end of version 2, 68 bytes
 * 0x44 u64  hash table size, compressed
 * 0x4C u64  block table size, compressed
 * 0x54 u64  hi-block table size, compressed
 * 0x5C u64  HET table size, compressed
 * 0x64 u64  BET table size, compressed
 * 0x6C u32  raw chunk size for MD5
 * 0x70      six 16-byte MD5 digests -- end of version 3, 208 bytes
 * </pre>
 *
 * <h2>Version numbering</h2>
 * {@code formatVersion} is the raw {@code wFormatVersion} field, so 0 is the
 * original 32-byte format. StormLib's constant names are offset by one from
 * this value; see {@code docs/mpq-format-notes.md}.
 *
 * @param headerOffset      where the header was found in the file.
 * @param headerSize        effective header size, after any repair.
 * @param formatVersion     raw {@code wFormatVersion}, 0 to 3.
 * @param archiveSize       archive size in bytes, clamped to what the file
 *                          actually holds.
 * @param sectorSizeShift   sector size exponent; sector size is
 *                          {@code 512 << sectorSizeShift}.
 * @param hashTablePosition hash table offset relative to
 *                          {@code headerOffset}.
 * @param blockTablePosition block table offset relative to
 *                          {@code headerOffset}.
 * @param hashTableEntries  number of hash table buckets.
 * @param blockTableEntries number of block table entries, clamped to what fits
 *                          in the file.
 * @param hiBlockTablePosition hi-block table offset, or 0 when absent.
 * @param hetTablePosition  HET table offset, or 0 when absent.
 * @param betTablePosition  BET table offset, or 0 when absent.
 * @param malformed         whether the header needed repair to be usable; the
 *                          archive is still readable, but its declared values
 *                          were not trustworthy.
 */
public record MpqHeader(
    long headerOffset,
    int headerSize,
    int formatVersion,
    long archiveSize,
    int sectorSizeShift,
    long hashTablePosition,
    long blockTablePosition,
    int hashTableEntries,
    int blockTableEntries,
    long hiBlockTablePosition,
    long hetTablePosition,
    long betTablePosition,
    boolean malformed) {

    /** {@code 'MPQ\x1A'}, the archive header signature. */
    public static final int ARCHIVE_SIGNATURE = 0x1A51504D;

    /** {@code 'MPQ\x1B'}, the user data header signature. */
    public static final int USER_DATA_SIGNATURE = 0x1B51504D;

    /** Header size for each format version, indexed by version. */
    public static final int[] SIZE_BY_VERSION = {32, 44, 68, 208};

    /** Highest {@code wFormatVersion} this library understands. */
    public static final int MAX_FORMAT_VERSION = 3;

    /** Candidate header positions are aligned to this. */
    public static final int ALIGNMENT = 0x200;

    /** StormLib's {@code HASH_TABLE_SIZE_MAX}. */
    public static final int MAX_HASH_TABLE_ENTRIES = 0x00080000;

    /**
     * Largest sector size shift that keeps {@code 512 << shift} inside a
     * positive {@code int}.
     */
    public static final int MAX_SECTOR_SIZE_SHIFT = 21;

    /** Size of one hash table entry. */
    public static final int HASH_ENTRY_SIZE = 16;

    /** Size of one block table entry. */
    public static final int BLOCK_ENTRY_SIZE = 16;

    /**
     * @return the archive's sector size in bytes.
     */
    public int sectorSize() {
        return 512 << sectorSizeShift;
    }

    /**
     * @return whether this archive uses HET/BET tables, which this library
     *         reads but does not write.
     */
    public boolean hasExtendedTables() {
        return hetTablePosition != 0 || betTablePosition != 0;
    }

    /**
     * @return absolute file offset of the hash table.
     */
    public long hashTableFileOffset() {
        return headerOffset + hashTablePosition;
    }

    /**
     * @return absolute file offset of the block table.
     */
    public long blockTableFileOffset() {
        return headerOffset + blockTablePosition;
    }

    /**
     * Locates and parses the archive header.
     *
     * @param source  the archive bytes.
     * @param forceV0 read the archive the way Warcraft III does: ignore the
     *                declared header size and format version, and ignore user
     *                data headers. Necessary for archives whose header was
     *                deliberately corrupted.
     * @return the parsed header.
     * @throws JMpqException if no usable archive header can be found.
     */
    public static MpqHeader parse(MpqSource source, boolean forceV0) throws JMpqException {
        final long offset = findHeader(source, forceV0);
        return parseAt(source, offset, forceV0);
    }

    /**
     * Scans for the archive header.
     * <p>
     * Headers sit on {@link #ALIGNMENT} boundaries. A user data header
     * ({@code MPQ\x1B}) redirects to the real one, and the redirect target is
     * validated before being followed: protected archives plant user data
     * headers pointing nowhere. In {@code forceV0} mode user data headers are
     * ignored entirely, as Warcraft III ignores them, and candidate headers are
     * checked for plausibility so a decoy does not win.
     */
    private static long findHeader(MpqSource source, boolean forceV0) throws JMpqException {
        final long size = source.size();

        for (long position = 0; position + 4 <= size; position += ALIGNMENT) {
            final int signature = source.i32(position);

            if (signature == ARCHIVE_SIGNATURE) {
                if (forceV0 && !isPlausible(source, position)) {
                    continue;
                }
                return position;
            }

            if (signature == USER_DATA_SIGNATURE && !forceV0 && source.contains(position + 8, 4)) {
                final long redirected = position + source.u32(position + 8);
                if (source.contains(redirected, 4) && source.i32(redirected) == ARCHIVE_SIGNATURE) {
                    return redirected;
                }
            }
        }

        throw new JMpqException("No MPQ archive header in " + source.origin() + ".");
    }

    /**
     * Cheap plausibility test for a candidate header, mirroring StormLib's
     * {@code ERROR_FAKE_MPQ_HEADER} checks: a header whose table positions fall
     * outside the file cannot be the real one.
     */
    private static boolean isPlausible(MpqSource source, long position) throws JMpqException {
        if (!source.contains(position, SIZE_BY_VERSION[0])) {
            return false;
        }
        final long hashTablePosition = source.u32(position + 0x10);
        final long blockTablePosition = source.u32(position + 0x14);
        final int hashTableEntries = source.i32(position + 0x18) & 0x0FFFFFFF;
        final int sectorShift = source.u16(position + 0x0E) & 0xFF;

        return hashTablePosition > 0
            && blockTablePosition > 0
            && hashTableEntries > 0
            && sectorShift <= MAX_SECTOR_SIZE_SHIFT
            && source.contains(position + hashTablePosition, 0)
            && source.contains(position + blockTablePosition, 0);
    }

    /**
     * Parses the header at a known offset.
     * <p>
     * Follows StormLib's {@code ConvertMpqHeaderToFormat4} in repairing rather
     * than rejecting: a version 0 archive whose declared header size is wrong
     * is read with the correct size and flagged malformed, because Storm.dll
     * ignores the field too. That is what makes the protected maps of issue #46
     * readable.
     */
    private static MpqHeader parseAt(MpqSource source, long offset, boolean forceV0) throws JMpqException {
        int declaredHeaderSize = source.i32(offset + 0x04);
        int formatVersion = source.u16(offset + 0x0C);
        boolean malformed = false;

        if (forceV0) {
            formatVersion = 0;
        }
        if (formatVersion > MAX_FORMAT_VERSION) {
            throw new JMpqException("MPQ format version " + formatVersion
                + " is not supported (highest known is " + MAX_FORMAT_VERSION + ").");
        }

        final int expectedHeaderSize = SIZE_BY_VERSION[formatVersion];
        int headerSize = declaredHeaderSize;
        if (headerSize != expectedHeaderSize) {
            // StormLib forces the version's own size and marks the archive
            // malformed rather than refusing to open it.
            headerSize = expectedHeaderSize;
            malformed = true;
        }
        if (!source.contains(offset, headerSize)) {
            throw new JMpqException("A version " + formatVersion + " header needs " + headerSize
                + " bytes but only " + (source.size() - offset) + " remain.");
        }

        // Only the low byte of wSectorSize is meaningful; a value in the high
        // byte means the field was tampered with.
        final int rawSectorShift = source.u16(offset + 0x0E);
        if ((rawSectorShift & 0xFF00) != 0) {
            malformed = true;
        }
        final int sectorSizeShift = rawSectorShift & 0xFF;
        if (sectorSizeShift > MAX_SECTOR_SIZE_SHIFT) {
            throw new JMpqException("Sector size shift " + sectorSizeShift
                + " would mean sectors of " + (512L << sectorSizeShift) + " bytes.");
        }

        long hashTablePosition = source.u32(offset + 0x10);
        long blockTablePosition = source.u32(offset + 0x14);
        final int hashTableEntries = source.i32(offset + 0x18) & 0x0FFFFFFF;
        int blockTableEntries = source.i32(offset + 0x1C);

        long archiveSize = source.u32(offset + 0x08);
        long hiBlockTablePosition = 0;
        long hetTablePosition = 0;
        long betTablePosition = 0;

        if (formatVersion >= 1) {
            hiBlockTablePosition = source.i64(offset + 0x20);
            // The high words extend the table offsets beyond 4 GiB.
            hashTablePosition |= (long) source.u16(offset + 0x28) << 32;
            blockTablePosition |= (long) source.u16(offset + 0x2A) << 32;
        }
        if (formatVersion >= 2) {
            archiveSize = source.i64(offset + 0x2C);
            betTablePosition = source.i64(offset + 0x34);
            hetTablePosition = source.i64(offset + 0x3C);
        }

        // The declared archive size is advisory: StormLib notes it "is ignored
        // by Storm.dll and can contain garbage value". Clamp it so it can never
        // drive an allocation larger than the file.
        final long available = source.size() - offset;
        if (archiveSize < 0 || archiveSize > available) {
            archiveSize = available;
        }

        if (hashTableEntries <= 0) {
            throw new JMpqException("Archive declares " + hashTableEntries + " hash table entries.");
        }
        if (hashTableEntries > MAX_HASH_TABLE_ENTRIES) {
            throw new JMpqException("Archive declares " + hashTableEntries
                + " hash table entries, above the " + MAX_HASH_TABLE_ENTRIES + " StormLib accepts.");
        }
        if (!source.contains(offset + hashTablePosition, (long) hashTableEntries * HASH_ENTRY_SIZE)) {
            throw new JMpqException("Hash table at " + (offset + hashTablePosition) + " spanning "
                + hashTableEntries + " entries runs past the end of " + source.origin() + ".");
        }
        if (!source.contains(offset + blockTablePosition, 0)) {
            throw new JMpqException("Block table position " + (offset + blockTablePosition)
                + " lies outside " + source.origin() + ".");
        }

        if (blockTableEntries < 0) {
            blockTableEntries = 0;
            malformed = true;
        }
        final long blockTableBytes = (long) blockTableEntries * BLOCK_ENTRY_SIZE;
        if (!source.contains(offset + blockTablePosition, blockTableBytes)) {
            // StormLib does exactly this: archives in the wild declare a block
            // table far larger than the file, and rejecting them would be
            // stricter than the game.
            final long fits = (source.size() - offset - blockTablePosition) / BLOCK_ENTRY_SIZE;
            blockTableEntries = (int) Math.max(0, fits);
            malformed = true;
        }

        return new MpqHeader(offset, headerSize, formatVersion, archiveSize, sectorSizeShift,
            hashTablePosition, blockTablePosition, hashTableEntries, blockTableEntries,
            hiBlockTablePosition, hetTablePosition, betTablePosition, malformed);
    }
}
