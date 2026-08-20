package systems.crigges.jmpq;

import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Decodes one file's content straight out of an {@link MpqSource}.
 * <p>
 * Sectors are read from the mapped segment one at a time, so decoding a file
 * never materialises its compressed form on the heap. The pre-2.0 path copied
 * every file's stored bytes into a {@code ByteBuffer} first and then decoded
 * from that.
 */
final class MpqFileReader {
    private final MpqSource source;
    private final MpqHeader header;

    MpqFileReader(MpqSource source, MpqHeader header) {
        this.source = source;
        this.header = header;
    }

    /**
     * @param entry the file to decode.
     * @return the file's content.
     * @throws IOException if the data is damaged or cannot be decoded.
     */
    byte[] read(MpqFileEntry entry) throws IOException {
        final java.io.ByteArrayOutputStream out =
            new java.io.ByteArrayOutputStream(Math.min(Math.max(32, entry.normalSize()), 64 * 1024));
        readTo(entry, out);
        return out.toByteArray();
    }

    /**
     * Decodes a file to a stream. The stream is flushed but never closed; it
     * belongs to the caller.
     *
     * @param entry  the file to decode.
     * @param target destination.
     * @throws IOException if the data is damaged or cannot be written.
     */
    void readTo(MpqFileEntry entry, OutputStream target) throws IOException {
        if (entry.normalSize() == 0) {
            target.flush();
            return;
        }
        if (entry.compressedSize() < 0) {
            throw new JMpqException("<" + entry.name() + "> declares a negative stored size "
                + entry.compressedSize() + ".");
        }

        final long base = header.headerOffset() + entry.filePosition();
        if (!source.contains(base, entry.compressedSize())) {
            throw new JMpqException("<" + entry.name() + "> spans [" + base + ", "
                + (base + entry.compressedSize()) + "), outside " + source.origin() + ".");
        }

        final int key = MpqNames.sectorKey(entry.name(), entry.flags(),
            entry.filePosition(), entry.normalSize());

        if (entry.has(MpqFileEntry.FLAG_SINGLE_UNIT)) {
            readSingleUnit(entry, target, base, key);
        } else if (entry.hasSectorOffsetTable()) {
            readSectors(entry, target, base, key);
        } else {
            readStored(entry, target, base, key);
        }
        target.flush();
    }

    /** One contiguous blob, optionally encoded, with no sector table. */
    private void readSingleUnit(MpqFileEntry entry, OutputStream target, long base, int key)
        throws IOException {
        final byte[] unit = source.bytes(base, entry.compressedSize());
        decrypt(entry, unit, key);

        if (entry.has(MpqFileEntry.FLAG_IMPLODED)) {
            target.write(CompressionUtil.explode(unit, entry.compressedSize(), entry.normalSize()));
        } else if (entry.has(MpqFileEntry.FLAG_COMPRESSED)) {
            target.write(CompressionUtil.decompress(unit, entry.compressedSize(), entry.normalSize(),
                header.formatVersion()));
        } else {
            target.write(unit);
        }
    }

    /** Stored verbatim: no sector table, no encoding. */
    private void readStored(MpqFileEntry entry, OutputStream target, long base, int key)
        throws IOException {
        // Nothing encodes this file, so its two sizes must agree. A SECTOR_CRC
        // file legitimately stores more, because of its checksum sector.
        if (entry.compressedSize() != entry.normalSize() && !entry.has(MpqFileEntry.FLAG_SECTOR_CRC)) {
            throw new JMpqException("Uncompressed <" + entry.name() + "> stores "
                + entry.compressedSize() + " bytes but declares " + entry.normalSize() + ".");
        }
        final byte[] data = source.bytes(base, entry.normalSize());
        decrypt(entry, data, key);
        target.write(data);
    }

    /** Sectored content preceded by a sector offset table. */
    private void readSectors(MpqFileEntry entry, OutputStream target, long base, int key)
        throws IOException {
        final int[] offsets = readSectorOffsets(entry, base, key);
        final boolean imploded = entry.has(MpqFileEntry.FLAG_IMPLODED);
        final int sectorSize = header.sectorSize();
        int remaining = entry.normalSize();

        for (int i = 0; i < dataSectorCount(entry); i++) {
            final int start = offsets[i];
            final int end = offsets[i + 1];
            if (start < 0 || end < start || end > entry.compressedSize()) {
                throw new JMpqException("Sector " + i + " of <" + entry.name() + "> spans ["
                    + start + ", " + end + "), outside its " + entry.compressedSize()
                    + " stored bytes.");
            }

            final byte[] sector = source.bytes(base + start, end - start);
            decrypt(entry, sector, key + i);

            final int expected = Math.min(remaining, sectorSize);
            final byte[] decoded = imploded
                ? CompressionUtil.explode(sector, end - start, expected)
                : CompressionUtil.decompress(sector, end - start, expected, header.formatVersion());
            target.write(decoded, 0, expected);
            remaining -= expected;
        }
    }

    /**
     * @return the sector offset table, decrypted if necessary. Encrypted tables
     *         use the key one below the first sector's.
     */
    private int[] readSectorOffsets(MpqFileEntry entry, long base, int key) throws IOException {
        final int entries = sectorOffsetEntryCount(entry);
        final long tableBytes = (long) entries * 4;
        if (tableBytes > entry.compressedSize()) {
            throw new JMpqException("<" + entry.name() + "> needs a " + tableBytes
                + " byte sector offset table but stores only " + entry.compressedSize() + " bytes.");
        }

        final byte[] raw = source.bytes(base, (int) tableBytes);
        decrypt(entry, raw, key - 1);

        final ByteBuffer table = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final int[] offsets = new int[entries];
        for (int i = 0; i < entries; i++) {
            offsets[i] = table.getInt();
        }
        return offsets;
    }

    /** Number of sectors holding file content. */
    private int dataSectorCount(MpqFileEntry entry) {
        return sectorCount(entry.normalSize(), header.sectorSize());
    }

    /**
     * Number of sector offset table entries: one per data sector, a terminator,
     * and one more delimiting the checksum sector when present.
     */
    private int sectorOffsetEntryCount(MpqFileEntry entry) {
        return dataSectorCount(entry) + 1 + (entry.has(MpqFileEntry.FLAG_SECTOR_CRC) ? 1 : 0);
    }

    /**
     * Ceiling division in {@code long} arithmetic. Both arguments are
     * {@code int}, and {@code size + sectorSize - 1} overflows negative for a
     * file within one sector of {@link Integer#MAX_VALUE}.
     *
     * @param size       file size in bytes.
     * @param sectorSize sector size in bytes.
     * @return number of sectors needed.
     */
    static int sectorCount(int size, int sectorSize) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }
        if (sectorSize <= 0) {
            throw new IllegalArgumentException("Sector size must be positive: " + sectorSize);
        }
        return (int) (((long) size + sectorSize - 1) / sectorSize);
    }

    private void decrypt(MpqFileEntry entry, byte[] data, int key) {
        if (entry.isEncrypted() && data.length > 0) {
            new MPQEncryption(key, true).processSingle(ByteBuffer.wrap(data));
        }
    }
}
