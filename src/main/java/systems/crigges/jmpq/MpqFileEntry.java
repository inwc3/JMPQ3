package systems.crigges.jmpq;

/**
 * One file in an archive: what it is called, where it lives, and how it is
 * encoded.
 * <p>
 * Replaces handing callers a raw block table entry. A {@code Block} is a
 * mutable row of the on-disk table with no idea what it is called; this is an
 * immutable value object that knows its name and locale, so callers no longer
 * have to correlate the two tables themselves.
 *
 * @param name           the file's path inside the archive, as the list file
 *                       spells it, or empty for a block reached without a name.
 * @param locale         Windows Language ID; 0 is the neutral default.
 * @param flags          raw block flags.
 * @param filePosition   offset of the file data relative to the archive header.
 * @param compressedSize bytes the file occupies in the archive.
 * @param normalSize     the file's size once decoded.
 * @param blockIndex     index of the block table row this came from.
 */
public record MpqFileEntry(
    String name,
    short locale,
    int flags,
    long filePosition,
    int compressedSize,
    int normalSize,
    int blockIndex) {

    /** PKWARE "implode" encoding; sectors carry no compression-type byte. */
    public static final int FLAG_IMPLODED = 0x00000100;

    /** Sectors are compressed and carry a leading compression-type byte. */
    public static final int FLAG_COMPRESSED = 0x00000200;

    /** Sector data is encrypted. */
    public static final int FLAG_ENCRYPTED = 0x00010000;

    /** {@code MPQ_FILE_KEY_V2}: the sector key folds in position and size. */
    public static final int FLAG_ADJUSTED_KEY = 0x00020000;

    /** One contiguous blob rather than sectors; no sector offset table. */
    public static final int FLAG_SINGLE_UNIT = 0x01000000;

    /** A deletion marker used by patch archives. */
    public static final int FLAG_DELETE_MARKER = 0x02000000;

    /** An extra sector of per-sector checksums follows the data. */
    public static final int FLAG_SECTOR_CRC = 0x04000000;

    /** The block is in use. */
    public static final int FLAG_EXISTS = 0x80000000;

    /**
     * @param flag one or more flag bits.
     * @return whether <em>all</em> of them are set.
     */
    public boolean has(int flag) {
        return (flags & flag) == flag;
    }

    /**
     * @return whether this block is in use.
     */
    public boolean exists() {
        return has(FLAG_EXISTS);
    }

    /**
     * @return whether the file data is encrypted.
     */
    public boolean isEncrypted() {
        return has(FLAG_ENCRYPTED);
    }

    /**
     * @return whether the sector key folds in the file's position and size, so
     *         that moving the file invalidates it.
     */
    public boolean hasAdjustedKey() {
        return has(FLAG_ADJUSTED_KEY);
    }

    /**
     * Whether the file data starts with a sector offset table.
     * <p>
     * Derived from the flags, per spec: a sector offset table exists when the
     * file is split into sectors and encoded. Single unit files hold one
     * contiguous blob, and files stored verbatim need no offsets. The pre-2.0
     * code inferred this from a sector count of 1, which conflated "no offset
     * table" with "empty file".
     *
     * @return true when a sector offset table precedes the data.
     */
    public boolean hasSectorOffsetTable() {
        return !has(FLAG_SINGLE_UNIT) && (has(FLAG_COMPRESSED) || has(FLAG_IMPLODED));
    }

    /**
     * @param name the name to attach.
     * @return a copy of this entry named {@code name}.
     */
    public MpqFileEntry withName(String name) {
        return new MpqFileEntry(name, locale, flags, filePosition, compressedSize, normalSize, blockIndex);
    }

    /**
     * @return a readable rendering of the flags, for diagnostics.
     */
    public String flagsToString() {
        final StringBuilder out = new StringBuilder();
        appendFlag(out, FLAG_EXISTS, "EXISTS");
        appendFlag(out, FLAG_SINGLE_UNIT, "SINGLE_UNIT");
        appendFlag(out, FLAG_COMPRESSED, "COMPRESSED");
        appendFlag(out, FLAG_IMPLODED, "IMPLODED");
        appendFlag(out, FLAG_ENCRYPTED, "ENCRYPTED");
        appendFlag(out, FLAG_ADJUSTED_KEY, "ADJUSTED_KEY");
        appendFlag(out, FLAG_SECTOR_CRC, "SECTOR_CRC");
        appendFlag(out, FLAG_DELETE_MARKER, "DELETE_MARKER");
        return out.isEmpty() ? "NONE" : out.toString();
    }

    private void appendFlag(StringBuilder out, int flag, String label) {
        if (has(flag)) {
            if (!out.isEmpty()) {
                out.append('|');
            }
            out.append(label);
        }
    }
}
