package org.inwc3.jmpq;

import systems.crigges.jmpq3.JMpqException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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
 * @param extended          the version 3 additions, or {@link Extended#NONE}.
 * @param userData          the user data header this archive sits behind, or
 *                          {@code null} when it starts the file.
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
    Extended extended,
    MpqUserData userData,
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

    /** Size of one hi-block table entry: the high word of a file position. */
    public static final int HI_BLOCK_ENTRY_SIZE = 2;

    /**
     * The version 3 header additions: compressed table sizes and MD5 digests.
     * <p>
     * From version 3 the tables may be stored compressed, which the position
     * fields alone cannot express — you need the stored length to know where a
     * table ends, and comparing it against the uncompressed length is the only
     * way to tell whether it is compressed at all. The digests let a reader
     * detect a damaged table before trusting it, which StormLib reports rather
     * than treating as fatal.
     *
     * @param hashTableCompressedSize   stored length of the hash table, or 0.
     * @param blockTableCompressedSize  stored length of the block table, or 0.
     * @param hiBlockTableCompressedSize stored length of the hi-block table.
     * @param hetTableCompressedSize    stored length of the HET table, or 0.
     * @param betTableCompressedSize    stored length of the BET table, or 0.
     * @param rawChunkSize              chunk size the MD5s were taken over.
     * @param md5BlockTable             expected digest of the block table.
     * @param md5HashTable              expected digest of the hash table.
     * @param md5HiBlockTable           expected digest of the hi-block table.
     * @param md5BetTable               expected digest of the BET table.
     * @param md5HetTable               expected digest of the HET table.
     * @param md5Header                 expected digest of the header itself.
     */
    public record Extended(
        long hashTableCompressedSize,
        long blockTableCompressedSize,
        long hiBlockTableCompressedSize,
        long hetTableCompressedSize,
        long betTableCompressedSize,
        int rawChunkSize,
        byte[] md5BlockTable,
        byte[] md5HashTable,
        byte[] md5HiBlockTable,
        byte[] md5BetTable,
        byte[] md5HetTable,
        byte[] md5Header) {

        /** Length of an MD5 digest. */
        public static final int DIGEST_SIZE = 16;

        /** What a header below version 3 carries: nothing. */
        public static final Extended NONE = new Extended(0, 0, 0, 0, 0, 0,
            new byte[0], new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);

        /**
         * Whether any digest was actually recorded.
         * <p>
         * Every version 3 header has all six fields present, so their lengths
         * say nothing: an archive that simply left them blank still carries
         * sixteen zero bytes each. Only a non-zero digest is a recorded one,
         * which is the same convention {@link #matchesDigest} applies, and
         * without it a blank version 3 header reported its tables as verified
         * against digests nobody had computed.
         *
         * @return whether validating the tables is meaningful.
         */
        public boolean hasDigests() {
            return isRecorded(md5HashTable) || isRecorded(md5BlockTable)
                || isRecorded(md5HiBlockTable) || isRecorded(md5HetTable)
                || isRecorded(md5BetTable) || isRecorded(md5Header);
        }

        /**
         * @param digest a digest field.
         * @return whether it holds a digest rather than being absent or blank.
         */
        static boolean isRecorded(byte[] digest) {
            return digest.length == DIGEST_SIZE && !isAllZero(digest);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Extended that)) {
                return false;
            }
            return hashTableCompressedSize == that.hashTableCompressedSize
                && blockTableCompressedSize == that.blockTableCompressedSize
                && hiBlockTableCompressedSize == that.hiBlockTableCompressedSize
                && hetTableCompressedSize == that.hetTableCompressedSize
                && betTableCompressedSize == that.betTableCompressedSize
                && rawChunkSize == that.rawChunkSize
                && Arrays.equals(md5BlockTable, that.md5BlockTable)
                && Arrays.equals(md5HashTable, that.md5HashTable)
                && Arrays.equals(md5HiBlockTable, that.md5HiBlockTable)
                && Arrays.equals(md5BetTable, that.md5BetTable)
                && Arrays.equals(md5HetTable, that.md5HetTable)
                && Arrays.equals(md5Header, that.md5Header);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(hashTableCompressedSize) * 31 + rawChunkSize;
        }

        @Override
        public String toString() {
            return "Extended[rawChunkSize=" + rawChunkSize
                + ", digests=" + (hasDigests() ? "present" : "absent") + "]";
        }
    }

    /**
     * @return the archive's sector size in bytes.
     */
    public int sectorSize() {
        return 512 << sectorSizeShift;
    }

    /**
     * @return whether this archive carries HET/BET tables in addition to, or
     *         instead of, the classic hash and block tables.
     */
    public boolean hasExtendedTables() {
        return hetTablePosition != 0 || betTablePosition != 0;
    }

    /**
     * @return whether a hi-block table extends file positions past 4 GiB.
     */
    public boolean hasHiBlockTable() {
        return hiBlockTablePosition != 0;
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
     * @return absolute file offset of the hi-block table.
     */
    public long hiBlockTableFileOffset() {
        return headerOffset + hiBlockTablePosition;
    }

    /**
     * @return absolute file offset of the HET table.
     */
    public long hetTableFileOffset() {
        return headerOffset + hetTablePosition;
    }

    /**
     * @return absolute file offset of the BET table.
     */
    public long betTableFileOffset() {
        return headerOffset + betTablePosition;
    }

    /**
     * Stored length of the hash table.
     * <p>
     * A version 3 archive may compress its tables, in which case the stored
     * length is shorter than the entries imply. Below version 3, and whenever
     * the field is absent or not shorter, the table is stored plain.
     *
     * @return bytes the hash table occupies in the file.
     */
    public long hashTableStoredSize() {
        final long plain = (long) hashTableEntries * HASH_ENTRY_SIZE;
        final long declared = extended.hashTableCompressedSize();
        return declared > 0 && declared < plain ? declared : plain;
    }

    /**
     * @return bytes the block table occupies in the file.
     */
    public long blockTableStoredSize() {
        final long plain = (long) blockTableEntries * BLOCK_ENTRY_SIZE;
        final long declared = extended.blockTableCompressedSize();
        return declared > 0 && declared < plain ? declared : plain;
    }

    /**
     * @return whether the hash table is stored compressed.
     */
    public boolean isHashTableCompressed() {
        return hashTableStoredSize() < (long) hashTableEntries * HASH_ENTRY_SIZE;
    }

    /**
     * @return whether the block table is stored compressed.
     */
    public boolean isBlockTableCompressed() {
        return blockTableStoredSize() < (long) blockTableEntries * BLOCK_ENTRY_SIZE;
    }

    /**
     * Checks the header against its own MD5 digest.
     * <p>
     * The digest covers the header up to but not including the digest field
     * itself, which sits at the very end of a version 3 header.
     *
     * @param source the archive bytes.
     * @return true when no digest was recorded, or when it matches.
     * @throws JMpqException if the header cannot be read.
     */
    public boolean verifyHeaderDigest(MpqSource source) throws JMpqException {
        if (extended.md5Header().length != Extended.DIGEST_SIZE) {
            return true;
        }
        final int covered = SIZE_BY_VERSION[3] - Extended.DIGEST_SIZE;
        return matchesDigest(source.bytes(headerOffset, covered), extended.md5Header());
    }

    /**
     * @param data   the bytes to digest.
     * @param digest the expected MD5, or an empty array to skip the check.
     * @return whether the digest matches, or true when there is nothing to
     *         compare against.
     */
    static boolean matchesDigest(byte[] data, byte[] digest) {
        if (!Extended.isRecorded(digest)) {
            // StormLib treats an all-zero digest as "not recorded" rather than
            // as the digest of these bytes.
            return true;
        }
        try {
            return Arrays.equals(MessageDigest.getInstance("MD5").digest(data), digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java runtime is required to provide MD5.
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    static boolean isAllZero(byte[] digest) {
        for (byte value : digest) {
            if (value != 0) {
                return false;
            }
        }
        return true;
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
        final Located located = findHeader(source, forceV0);
        return parseAt(source, located, forceV0);
    }

    /** Where a header was found, and what preceded it. */
    private record Located(long offset, MpqUserData userData) {
    }

    /**
     * Scans for the archive header.
     * <p>
     * Headers sit on {@link #ALIGNMENT} boundaries. A user data header
     * ({@code MPQ\x1B}) redirects to the real one, and the redirect target is
     * validated before being followed: protected archives plant user data
     * headers pointing nowhere. In {@code forceV0} mode user data headers are
     * ignored entirely, as Warcraft III ignores them.
     * <p>
     * P2-5b: a candidate that fails a cheap plausibility test does not end the
     * scan. Protected archives plant decoy {@code MPQ\x1A} signatures precisely
     * so that a reader commits to the first one it sees and then fails. Keeping
     * the first candidate as a fallback means this can only ever find a header
     * where the old scan found one, never fewer.
     */
    private static Located findHeader(MpqSource source, boolean forceV0) throws JMpqException {
        final long size = source.size();
        Located fallback = null;

        for (long position = 0; position + 4 <= size; position += ALIGNMENT) {
            final int signature = source.i32(position);

            if (signature == ARCHIVE_SIGNATURE) {
                final Located candidate = new Located(position, null);
                if (isPlausible(source, position, forceV0)) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
                continue;
            }

            if (signature == USER_DATA_SIGNATURE && !forceV0) {
                final MpqUserData userData = MpqUserData.readAt(source, position);
                if (userData == null) {
                    continue;
                }
                final long redirected = userData.archiveHeaderOffset();
                if (source.contains(redirected, 4) && source.i32(redirected) == ARCHIVE_SIGNATURE) {
                    final Located candidate = new Located(redirected, userData);
                    if (isPlausible(source, redirected, forceV0)) {
                        return candidate;
                    }
                    if (fallback == null) {
                        fallback = candidate;
                    }
                }
            }
        }

        if (fallback != null) {
            return fallback;
        }
        throw new JMpqException("No MPQ archive header in " + source.origin() + ".");
    }

    /**
     * Cheap plausibility test for a candidate header, mirroring StormLib's
     * {@code ERROR_FAKE_MPQ_HEADER} checks.
     *
     * <h4>The invariant</h4>
     * This must reject only headers that {@link #parseAt} would reject anyway.
     * Rejecting anything more is not a stricter filter, it is a bug: the scan
     * moves on and can settle on a decoy planted earlier in the file, which is
     * the reverse of the point. Two rounds of review found exactly that, both
     * times because a condition here was tightened past what the parser
     * actually requires.
     * <p>
     * So the checks below are a screen of the parser's own rejections, in the
     * same order and with the same thresholds, and nothing else. Everything the
     * parser merely <em>repairs</em> — a wrong header size, an oversized block
     * table, an out-of-range hi-block position — is deliberately not screened
     * here, because such a header is still perfectly usable.
     */
    private static boolean isPlausible(MpqSource source, long position, boolean forceV0)
        throws JMpqException {
        if (!source.contains(position, SIZE_BY_VERSION[0])) {
            return false;
        }

        // The version the parser will settle on: forceV0 ignores what the header
        // declares, which is the whole point of it.
        final int version = forceV0 ? 0 : source.u16(position + 0x0C);
        if (version > MAX_FORMAT_VERSION) {
            return false;
        }
        // The parser reads the whole header for that version before anything
        // else, and cannot repair its way out of the bytes not being there.
        // This cannot currently change which header is chosen: a candidate
        // without room for its own header is necessarily the last one in the
        // file, so there is no later header to reach and the fallback returns
        // this one anyway. It is here to keep the screen a faithful mirror of
        // the parser rather than a set of conditions that happen to matter.
        if (!source.contains(position, SIZE_BY_VERSION[version])) {
            return false;
        }

        final int sectorShift = source.u16(position + 0x0E) & 0xFF;
        if (sectorShift > MAX_SECTOR_SIZE_SHIFT) {
            return false;
        }

        final int hashTableEntries = source.i32(position + 0x18) & 0x0FFFFFFF;
        if (hashTableEntries <= 0 || hashTableEntries > MAX_HASH_TABLE_ENTRIES) {
            return false;
        }

        final long hashTablePosition = source.u32(position + 0x10);
        final long blockTablePosition = source.u32(position + 0x14);
        return hashTablePosition > 0
            && blockTablePosition > 0
            && source.contains(position + hashTablePosition,
                candidateHashTableBytes(source, position, hashTableEntries, forceV0))
            // A block table that runs past the end is clamped rather than
            // refused, so only its position has to be in the file -- unless it
            // is compressed, where the stored length is all there is to go on.
            && source.contains(position + blockTablePosition,
                candidateCompressedBlockTableBytes(source, position, forceV0));
    }

    /**
     * Stored length of a candidate's block table, but only when compressing it
     * makes that length load-bearing.
     *
     * @param source   the archive bytes.
     * @param position the candidate header offset.
     * @param forceV0  whether the archive will be read as version 0 regardless.
     * @return bytes to require at the block table position, or 0 to require only
     *         that the position itself is in the file.
     */
    private static long candidateCompressedBlockTableBytes(MpqSource source, long position,
                                                           boolean forceV0) throws JMpqException {
        if (forceV0 || source.u16(position + 0x0C) != 3
            || !source.contains(position, SIZE_BY_VERSION[3])) {
            return 0;
        }
        final long plain = (long) source.i32(position + 0x1C) * BLOCK_ENTRY_SIZE;
        final long stored = source.i64(position + 0x4C);
        return stored > 0 && stored < plain ? stored : 0;
    }

    /**
     * How many bytes a candidate header's hash table actually occupies.
     * <p>
     * From version 3 a hash table may be stored compressed, so requiring room
     * for the uncompressed table would rule out a valid header whose compressed
     * table sits near the end of the file — and if a decoy header came first,
     * the scan would then settle on the decoy. That is the reverse of what this
     * check is for, so the stored length is used where the header declares one.
     *
     * Only format version 3 has the field, and only if this candidate is going
     * to be read as version 3 -- under {@code forceV0} it will be read as
     * version 0, where those bytes mean nothing.
     *
     * @param source   the archive bytes.
     * @param position the candidate header offset.
     * @param entries  declared hash table entries.
     * @param forceV0  whether the archive will be read as version 0 regardless.
     * @return bytes to require at the hash table position.
     */
    private static long candidateHashTableBytes(MpqSource source, long position, int entries,
                                                boolean forceV0) throws JMpqException {
        final long plain = (long) entries * HASH_ENTRY_SIZE;
        if (forceV0 || source.u16(position + 0x0C) != 3
            || !source.contains(position, SIZE_BY_VERSION[3])) {
            return plain;
        }
        final long stored = source.i64(position + 0x44);
        return stored > 0 && stored < plain ? stored : plain;
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
    private static MpqHeader parseAt(MpqSource source, Located located, boolean forceV0)
        throws JMpqException {
        final long offset = located.offset();
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
        Extended extended = Extended.NONE;

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
        if (formatVersion >= 3) {
            extended = new Extended(
                source.i64(offset + 0x44),
                source.i64(offset + 0x4C),
                source.i64(offset + 0x54),
                source.i64(offset + 0x5C),
                source.i64(offset + 0x64),
                source.i32(offset + 0x6C),
                source.bytes(offset + 0x70, Extended.DIGEST_SIZE),
                source.bytes(offset + 0x80, Extended.DIGEST_SIZE),
                source.bytes(offset + 0x90, Extended.DIGEST_SIZE),
                source.bytes(offset + 0xA0, Extended.DIGEST_SIZE),
                source.bytes(offset + 0xB0, Extended.DIGEST_SIZE),
                source.bytes(offset + 0xC0, Extended.DIGEST_SIZE));
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

        if (hiBlockTablePosition < 0
            || (hiBlockTablePosition != 0 && !source.contains(offset + hiBlockTablePosition, 0))) {
            // A position outside the file cannot be a table. Dropping it leaves
            // the low words, which is what a version 0 reader would use.
            hiBlockTablePosition = 0;
            malformed = true;
        }

        final MpqHeader header = new MpqHeader(offset, headerSize, formatVersion, archiveSize,
            sectorSizeShift, hashTablePosition, blockTablePosition, hashTableEntries,
            blockTableEntries, hiBlockTablePosition, hetTablePosition, betTablePosition,
            extended, located.userData(), malformed);

        if (!source.contains(offset + hashTablePosition, header.hashTableStoredSize())) {
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
        if (!source.contains(offset + blockTablePosition, header.blockTableStoredSize())) {
            // StormLib does exactly this: archives in the wild declare a block
            // table far larger than the file, and rejecting them would be
            // stricter than the game. A compressed table cannot be reinterpreted
            // this way, because its entry count is not implied by its length.
            if (header.isBlockTableCompressed()) {
                throw new JMpqException("Compressed block table at "
                    + (offset + blockTablePosition) + " spanning "
                    + header.blockTableStoredSize() + " bytes runs past the end of "
                    + source.origin() + ".");
            }
            final long fits = (source.size() - offset - blockTablePosition) / BLOCK_ENTRY_SIZE;
            blockTableEntries = (int) Math.max(0, fits);
            malformed = true;
        }

        return new MpqHeader(offset, headerSize, formatVersion, archiveSize, sectorSizeShift,
            hashTablePosition, blockTablePosition, hashTableEntries, blockTableEntries,
            hiBlockTablePosition, hetTablePosition, betTablePosition,
            extended, located.userData(), malformed);
    }
}
