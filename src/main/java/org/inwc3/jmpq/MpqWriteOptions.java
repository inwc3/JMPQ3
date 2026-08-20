package org.inwc3.jmpq;

import systems.crigges.jmpq3.compression.RecompressOptions;

/**
 * How to build an archive.
 *
 * <h2>Explicit format selection (P1-6)</h2>
 * {@link #formatVersion} is chosen, never inherited. The pre-2.0 writer took
 * whatever version it had read and then emitted a version 0 header body for it,
 * so a version 2 archive was rewritten with a 208-byte header holding
 * 32 bytes of meaning. Only versions 0 and 1 can be written; versions 2 and 3
 * are read-only because they need HET/BET tables.
 *
 * <h2>Sector size and verbatim copies</h2>
 * A file can only be copied with its stored bytes intact when the target keeps
 * the source's sector size, because a sector offset table is expressed in the
 * archive's sector size and an archive has exactly one. Asking for a different
 * {@link #sectorSizeShift} therefore forces every file to be re-encoded. The
 * writer works this out itself; the caller only states the intent.
 *
 * @param formatVersion   0 for the 32-byte header, 1 for the 44-byte header
 *                        with hi-word table offsets.
 * @param sectorSizeShift sector size exponent; the sector size is
 *                        {@code 512 << sectorSizeShift}. The usual value for
 *                        Warcraft III is 3, giving 4 KiB sectors.
 * @param recompression   how to compress file data.
 * @param writeListfile   whether to emit a {@code (listfile)} naming the
 *                        archive's contents. Without one the archive cannot be
 *                        enumerated, and a later rebuild would lose the names.
 * @param keepPrefix      whether to preserve bytes that preceded the archive
 *                        header in the source. Warcraft III maps carry a
 *                        512-byte prefix.
 * @param hashTableCapacity explicit hash table capacity, or 0 to size it from
 *                        the file count. An extension point for protection
 *                        tooling that wants a maximised table (P1-8).
 * @param extraBlockEntries extra unused block table slots to emit beyond the
 *                        files written, or 0 for none. The other half of the
 *                        P1-8 extension point.
 */
public record MpqWriteOptions(
    int formatVersion,
    int sectorSizeShift,
    RecompressOptions recompression,
    boolean writeListfile,
    boolean keepPrefix,
    int hashTableCapacity,
    int extraBlockEntries) {

    /** Highest format version this library can write. */
    public static final int MAX_WRITABLE_VERSION = 1;

    /** Sector size shift used by Warcraft III maps: 4 KiB sectors. */
    public static final int DEFAULT_SECTOR_SIZE_SHIFT = 3;

    public MpqWriteOptions {
        if (formatVersion < 0 || formatVersion > MAX_WRITABLE_VERSION) {
            throw new IllegalArgumentException("Can only write format version 0 or 1, not "
                + formatVersion + ". Versions 2 and above need HET/BET tables and are read-only.");
        }
        if (sectorSizeShift < 0 || sectorSizeShift > MpqHeader.MAX_SECTOR_SIZE_SHIFT) {
            throw new IllegalArgumentException("Sector size shift must be between 0 and "
                + MpqHeader.MAX_SECTOR_SIZE_SHIFT + ", was " + sectorSizeShift + ".");
        }
        if (hashTableCapacity < 0) {
            throw new IllegalArgumentException("Hash table capacity cannot be negative.");
        }
        if (hashTableCapacity > 0 && Integer.bitCount(hashTableCapacity) != 1) {
            throw new IllegalArgumentException("Hash table capacity must be a power of two, was "
                + hashTableCapacity + ".");
        }
        if (hashTableCapacity > MpqHeader.MAX_HASH_TABLE_ENTRIES) {
            throw new IllegalArgumentException("Hash table capacity " + hashTableCapacity
                + " exceeds the " + MpqHeader.MAX_HASH_TABLE_ENTRIES + " maximum.");
        }
        if (extraBlockEntries < 0) {
            throw new IllegalArgumentException("Extra block entries cannot be negative.");
        }
        if (recompression == null) {
            throw new IllegalArgumentException("Recompression options are required.");
        }
    }

    /**
     * @return options producing a version 0 archive with 4 KiB sectors, stored
     *         compression and a list file: what a Warcraft III map wants.
     */
    public static MpqWriteOptions defaults() {
        return new MpqWriteOptions(0, DEFAULT_SECTOR_SIZE_SHIFT, new RecompressOptions(false),
            true, true, 0, 0);
    }

    /**
     * @return options that re-encode every file with maximum deflate.
     */
    public static MpqWriteOptions recompressed() {
        return defaults().withRecompression(new RecompressOptions(true));
    }

    /**
     * @return the sector size in bytes.
     */
    public int sectorSize() {
        return 512 << sectorSizeShift;
    }

    /**
     * @return the header size this format version requires.
     */
    public int headerSize() {
        return MpqHeader.SIZE_BY_VERSION[formatVersion];
    }

    /**
     * @param version 0 or 1.
     * @return a copy targeting that format version.
     */
    public MpqWriteOptions withFormatVersion(int version) {
        return new MpqWriteOptions(version, sectorSizeShift, recompression, writeListfile,
            keepPrefix, hashTableCapacity, extraBlockEntries);
    }

    /**
     * @param shift sector size exponent.
     * @return a copy using that sector size.
     */
    public MpqWriteOptions withSectorSizeShift(int shift) {
        return new MpqWriteOptions(formatVersion, shift, recompression, writeListfile,
            keepPrefix, hashTableCapacity, extraBlockEntries);
    }

    /**
     * @param options compression strategy.
     * @return a copy using it.
     */
    public MpqWriteOptions withRecompression(RecompressOptions options) {
        return new MpqWriteOptions(formatVersion, sectorSizeShift, options, writeListfile,
            keepPrefix, hashTableCapacity, extraBlockEntries);
    }

    /**
     * @param write whether to emit a {@code (listfile)}.
     * @return a copy with that setting.
     */
    public MpqWriteOptions withListfile(boolean write) {
        return new MpqWriteOptions(formatVersion, sectorSizeShift, recompression, write,
            keepPrefix, hashTableCapacity, extraBlockEntries);
    }

    /**
     * @param keep whether to preserve bytes before the archive header.
     * @return a copy with that setting.
     */
    public MpqWriteOptions withPrefix(boolean keep) {
        return new MpqWriteOptions(formatVersion, sectorSizeShift, recompression, writeListfile,
            keep, hashTableCapacity, extraBlockEntries);
    }

    /**
     * Forces a hash table capacity instead of sizing it from the file count.
     * <p>
     * Exists so protection tooling can emit a maximised table without that
     * being a first-class concern of this library (P1-8).
     *
     * @param capacity power-of-two capacity, or 0 to size automatically.
     * @return a copy with that capacity.
     */
    public MpqWriteOptions withHashTableCapacity(int capacity) {
        return new MpqWriteOptions(formatVersion, sectorSizeShift, recompression, writeListfile,
            keepPrefix, capacity, extraBlockEntries);
    }

    /**
     * Emits extra unused block table slots beyond the files written.
     * <p>
     * The second half of the P1-8 extension point: enough for a protection tool
     * to add its own entries without this library knowing what they are for.
     *
     * @param extra number of extra slots.
     * @return a copy with that many extra slots.
     */
    public MpqWriteOptions withExtraBlockEntries(int extra) {
        return new MpqWriteOptions(formatVersion, sectorSizeShift, recompression, writeListfile,
            keepPrefix, hashTableCapacity, extra);
    }
}
