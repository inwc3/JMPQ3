package systems.crigges.jmpq3;

import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A single file inside an MPQ archive: its block table entry plus the raw bytes
 * it occupies, with the logic to turn those into file content.
 *
 * <h2>Stream ownership</h2>
 * {@link #extractToOutputStream(OutputStream)} writes and flushes, but never
 * closes: the stream belongs to the caller. The pre-2.0 implementation closed
 * every stream handed to it, which silently truncated callers that were writing
 * several files into one stream.
 */
public class MpqFile {
    public static final int IMPLODED = 0x00000100;
    public static final int COMPRESSED = 0x00000200;
    public static final int ENCRYPTED = 0x00010000;
    /** {@code MPQ_FILE_KEY_V2}: the sector key folds in position and size. */
    public static final int ADJUSTED_ENCRYPTED = 0x00020000;
    public static final int SINGLE_UNIT = 0x01000000;
    public static final int DELETED = 0x02000000;
    /** {@code MPQ_FILE_SECTOR_CRC}: an extra sector of per-sector checksums. */
    public static final int SECTOR_CRC = 0x04000000;
    public static final int EXISTS = 0x80000000;

    /** Flags describing encryption, cleared when a file is stored plain. */
    private static final int ENCRYPTION_FLAGS = ENCRYPTED | ADJUSTED_ENCRYPTED;

    /**
     * Largest buffer to reserve up front from a size the archive claims. Beyond
     * this the buffer grows as content actually arrives, so a malformed block
     * cannot turn into a huge allocation.
     */
    private static final int MAX_PREALLOCATION = 64 * 1024;

    private final ByteBuffer buf;
    private final Block block;
    private final String name;
    private final boolean isEncrypted;
    private final int sectorSize;
    private final int compressedSize;
    private final int normalSize;
    private final int flags;
    private final int baseKey;

    /**
     * The containing archive's {@code wFormatVersion}.
     * <p>
     * Decides how a sector's compression-type byte is interpreted: version 0
     * and 1 read it as a bit mask, version 2 and above match it exactly against
     * a closed set in which {@code 0x12} means standalone LZMA. See
     * {@link CompressionUtil}.
     */
    private final int formatVersion;

    /**
     * @param buf        the file's raw bytes, exactly
     *                   {@link Block#getCompressedSize()} of them.
     * @param b          the file's block table entry.
     * @param sectorSize the archive's sector size in bytes.
     * @param name       the file's path, needed to derive its encryption key;
     *                   may be empty for an unnamed block, in which case
     *                   encrypted content cannot be decoded.
     * @throws IOException if the file cannot be prepared for reading.
     * @deprecated assumes format version 0, so a version 2+ archive's LZMA
     *             sectors are misread as {@code BZIP2 | ZLIB}. Use
     *             {@link #MpqFile(ByteBuffer, Block, int, String, int)}.
     */
    @Deprecated
    public MpqFile(ByteBuffer buf, Block b, int sectorSize, String name) throws IOException {
        this(buf, b, sectorSize, name, 0);
    }

    /**
     * @param buf           the file's raw bytes, exactly
     *                      {@link Block#getCompressedSize()} of them.
     * @param b             the file's block table entry.
     * @param sectorSize    the archive's sector size in bytes.
     * @param name          the file's path, needed to derive its encryption
     *                      key; may be empty for an unnamed block.
     * @param formatVersion the containing archive's {@code wFormatVersion}.
     * @throws IOException if the file cannot be prepared for reading.
     */
    public MpqFile(ByteBuffer buf, Block b, int sectorSize, String name, int formatVersion) throws IOException {
        if (sectorSize <= 0) {
            throw new JMpqException("Archive sector size must be positive, was " + sectorSize + ".");
        }
        this.buf = buf;
        this.block = b;
        this.sectorSize = sectorSize;
        this.name = name;
        this.compressedSize = b.getCompressedSize();
        this.normalSize = b.getNormalSize();
        this.flags = b.getFlags();
        this.isEncrypted = b.hasFlag(ENCRYPTED);
        this.formatVersion = formatVersion;
        this.baseKey = MpqNames.sectorKey(name, flags, b.getFilePosition(), normalSize);
    }

    /**
     * @return the number of bytes this file occupies in the archive.
     */
    public int getCompressedSize() {
        return compressedSize;
    }

    /**
     * @return the file's size once decoded.
     */
    public int getNormalSize() {
        return normalSize;
    }

    public int getFlags() {
        return flags;
    }

    public String getName() {
        return name;
    }

    /**
     * Writes this file's content to {@code target}, creating it if necessary.
     *
     * @param target destination file.
     * @throws IOException on any I/O or decoding failure.
     */
    public void extractToFile(File target) throws IOException {
        extractToPath(target.toPath());
    }

    /**
     * Writes this file's content to {@code target}, creating it if necessary.
     * <p>
     * Zero-length files produce an empty file rather than nothing at all.
     *
     * @param target destination path.
     * @throws IOException on any I/O or decoding failure.
     */
    public void extractToPath(Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            extractToOutputStream(out);
        }
    }

    /**
     * @return this file's decoded content.
     * @throws IOException on any decoding failure.
     */
    public byte[] extractToBytes() throws IOException {
        // normalSize comes from the block table and is not trusted: a crafted
        // archive can declare gigabytes for a block holding a handful of bytes.
        // Cap the initial capacity and let the stream grow with real output.
        final ByteArrayOutputStream out =
            new ByteArrayOutputStream(Math.min(Math.max(32, normalSize), MAX_PREALLOCATION));
        extractToOutputStream(out);
        return out.toByteArray();
    }

    /**
     * Writes this file's decoded content to the given stream.
     * <p>
     * The stream is flushed but <em>not</em> closed; it belongs to the caller.
     *
     * @param writer destination stream.
     * @throws IOException on any I/O or decoding failure.
     */
    public void extractToOutputStream(OutputStream writer) throws IOException {
        if (normalSize == 0) {
            // Nothing to write. Not a special case in the format: the block
            // simply has no data.
            writer.flush();
            return;
        }

        if (block.hasFlag(SINGLE_UNIT)) {
            extractSingleUnit(writer);
        } else if (block.hasFlag(IMPLODED)) {
            extractSectors(writer, true);
        } else if (block.hasFlag(COMPRESSED)) {
            extractSectors(writer, false);
        } else {
            extractStored(writer);
        }
        writer.flush();
    }

    /** One contiguous blob, optionally compressed, with no sector table. */
    private void extractSingleUnit(OutputStream writer) throws IOException {
        final byte[] unit = readAt(0, compressedSize);
        decrypt(unit, baseKey);

        if (block.hasFlag(IMPLODED)) {
            writer.write(CompressionUtil.explode(unit, compressedSize, normalSize));
        } else if (block.hasFlag(COMPRESSED)) {
            writer.write(CompressionUtil.decompress(unit, compressedSize, normalSize, formatVersion));
        } else {
            writer.write(unit);
        }
    }

    /**
     * Stored verbatim: no offset table, no compression, but still sectored.
     * <p>
     * The absence of a compression flag removes the offset table, not the
     * sectors, and an encrypted file still encrypts each sector with its own
     * {@code key + index}. StormLib's {@code ReadMpqSectors} decrypts inside
     * the per-sector loop regardless of the compression flags. Decrypting the
     * whole file with the base key decoded the first sector and corrupted every
     * one after it; no test fixture has such a file, so this went unnoticed.
     */
    private void extractStored(OutputStream writer) throws IOException {
        // Nothing encodes this file, so its two sizes must agree. Checking says
        // so explicitly instead of silently returning short content.
        // A SECTOR_CRC file carries an extra checksum sector, so its stored
        // size is legitimately larger; everything else must match exactly.
        if (compressedSize != normalSize && !block.hasFlag(SECTOR_CRC)) {
            throw new JMpqException("Uncompressed <" + name + "> stores " + compressedSize
                + " bytes but declares " + normalSize + ".");
        }
        int remaining = normalSize;
        for (int i = 0; remaining > 0; i++) {
            final int length = Math.min(sectorSize, remaining);
            final byte[] sector = readAt((long) i * sectorSize, length);
            decrypt(sector, baseKey + i);
            writer.write(sector);
            remaining -= length;
        }
    }

    /**
     * Sectored content preceded by a sector offset table.
     *
     * @param imploded whether sectors are PKWARE imploded, which means they
     *                 carry no leading compression-type byte.
     */
    private void extractSectors(OutputStream writer, boolean imploded) throws IOException {
        final int[] offsets = readSectorOffsets();
        int remaining = normalSize;

        for (int i = 0; i < dataSectorCount(); i++) {
            final int start = offsets[i];
            final int end = offsets[i + 1];
            validateSectorRange(i, start, end);

            final byte[] sector = readAt(start, end - start);
            decrypt(sector, baseKey + i);

            final int expected = Math.min(remaining, sectorSize);
            final byte[] decoded = imploded
                ? CompressionUtil.explode(sector, end - start, expected)
                : CompressionUtil.decompress(sector, end - start, expected, formatVersion);
            writer.write(decoded, 0, expected);
            remaining -= expected;
        }
    }

    /**
     * @return the sector offset table, {@link #sectorOffsetEntryCount()}
     *         entries, decrypted if necessary.
     */
    private int[] readSectorOffsets() throws JMpqException {
        final int entries = sectorOffsetEntryCount();
        final byte[] raw = readAt(0, entries * 4);
        // The sector offset table is encrypted with the key one below the first
        // sector's key.
        decrypt(raw, baseKey - 1);

        final ByteBuffer table = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        final int[] offsets = new int[entries];
        for (int i = 0; i < entries; i++) {
            offsets[i] = table.getInt();
        }
        return offsets;
    }

    /** Number of sectors holding file content. */
    private int dataSectorCount() {
        return sectorCount(normalSize, sectorSize);
    }

    /**
     * Ceiling division of a file size by a sector size.
     * <p>
     * Done in {@code long} arithmetic deliberately. Both arguments are
     * {@code int}, and {@code size + sectorSize - 1} overflows to a negative
     * number for a file within about one sector of {@link Integer#MAX_VALUE} --
     * roughly 2 GiB with 4 KiB sectors, or just over 1 GiB at the largest
     * sector size the format allows. The result always fits in an {@code int}
     * because it is at most {@code size}.
     *
     * @param size       file size in bytes; must not be negative.
     * @param sectorSize sector size in bytes; must be positive.
     * @return the number of sectors needed to hold {@code size} bytes.
     */
    public static int sectorCount(int size, int sectorSize) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }
        if (sectorSize <= 0) {
            throw new IllegalArgumentException("Sector size must be positive: " + sectorSize);
        }
        return (int) (((long) size + sectorSize - 1) / sectorSize);
    }

    /**
     * Number of entries in the sector offset table: one per data sector, plus a
     * terminator, plus one more delimiting the checksum sector when present.
     */
    private int sectorOffsetEntryCount() {
        return dataSectorCount() + 1 + (block.hasFlag(SECTOR_CRC) ? 1 : 0);
    }

    private void validateSectorRange(int index, int start, int end) throws JMpqException {
        if (start < 0 || end < start || end > compressedSize) {
            throw new JMpqException("Sector " + index + " of <" + name + "> spans [" + start + ", " + end
                + "), which is outside the file's " + compressedSize + " stored bytes.");
        }
    }

    private byte[] readAt(long offset, int length) throws JMpqException {
        if (offset < 0 || length < 0 || offset + length > buf.limit()) {
            throw new JMpqException("Read of " + length + " bytes at " + offset + " in <" + name
                + "> exceeds its " + buf.limit() + " stored bytes.");
        }
        final byte[] out = new byte[length];
        buf.position((int) offset);
        buf.get(out);
        return out;
    }

    private void decrypt(byte[] data, int key) {
        if (isEncrypted && data.length > 0) {
            new MPQEncryption(key, true).processSingle(ByteBuffer.wrap(data));
        }
    }




    @Override
    public String toString() {
        return "MpqFile [sectorSize=" + sectorSize + ", compressedSize=" + compressedSize
            + ", normalSize=" + normalSize + ", flags=" + flags + ", name=" + name + "]";
    }
}
