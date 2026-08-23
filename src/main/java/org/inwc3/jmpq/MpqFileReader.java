package org.inwc3.jmpq;

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
    private final boolean verifyChecksums;

    MpqFileReader(MpqSource source, MpqHeader header, boolean verifyChecksums) {
        this.source = source;
        this.header = header;
        this.verifyChecksums = verifyChecksums;
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
            // Nothing encodes it, so the same size invariant applies as for a
            // stored file; without this a damaged block table silently yields
            // the wrong number of bytes.
            if (entry.compressedSize() != entry.normalSize()) {
                throw new JMpqException("Uncompressed single-unit <" + entry.name() + "> stores "
                    + entry.compressedSize() + " bytes but declares " + entry.normalSize() + ".");
            }
            target.write(unit);
        }
    }

    /**
     * Stored verbatim: no offset table, no encoding, but still sectored.
     * <p>
     * The absence of a compression flag removes the offset table, not the
     * sectors: the file still occupies fixed-size sectors and, when encrypted,
     * each is encrypted with its own {@code key + index}. StormLib's
     * {@code ReadMpqSectors} decrypts inside the per-sector loop regardless of
     * the compression flags, which only decide whether an offset table is
     * consulted. Decrypting the whole file with the base key therefore decodes
     * the first sector and corrupts every one after it.
     */
    private void readStored(MpqFileEntry entry, OutputStream target, long base, int key)
        throws IOException {
        // Nothing encodes this file, so its two sizes must agree. A SECTOR_CRC
        // file legitimately stores more, because of its checksum sector.
        if (entry.compressedSize() != entry.normalSize() && !entry.has(MpqFileEntry.FLAG_SECTOR_CRC)) {
            throw new JMpqException("Uncompressed <" + entry.name() + "> stores "
                + entry.compressedSize() + " bytes but declares " + entry.normalSize() + ".");
        }

        final int sectorSize = header.sectorSize();
        int remaining = entry.normalSize();
        for (int i = 0; remaining > 0; i++) {
            final int length = Math.min(sectorSize, remaining);
            final byte[] sector = source.bytes(base + (long) i * sectorSize, length);
            decrypt(entry, sector, key + i);
            target.write(sector);
            remaining -= length;
        }
    }

    /** Sectored content preceded by a sector offset table. */
    private void readSectors(MpqFileEntry entry, OutputStream target, long base, int key)
        throws IOException {
        final int[] offsets = readSectorOffsets(entry, base, key);
        final boolean imploded = entry.has(MpqFileEntry.FLAG_IMPLODED);
        final int sectorSize = header.sectorSize();
        final int[] checksums = readSectorChecksums(entry, offsets, base);
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
            verifyChecksum(entry, checksums, i, sector);

            final int expected = Math.min(remaining, sectorSize);
            final byte[] decoded = imploded
                ? CompressionUtil.explode(sector, end - start, expected)
                : CompressionUtil.decompress(sector, end - start, expected, header.formatVersion());
            target.write(decoded, 0, expected);
            remaining -= expected;
        }
    }

    /**
     * P2-3: the per-sector checksum table of a {@code SECTOR_CRC} file.
     * <p>
     * Despite the flag's name the checksums are <em>Adler-32</em>, seeded 0,
     * taken over each sector as stored minus its encryption - that is, after
     * decrypting but before decompressing. StormLib's {@code ReadMpqSectors}
     * computes {@code adler32(0, pbInSector, dwRawBytesInThisSector)} at exactly
     * that point, and its writer takes the same value over the compressed
     * buffer, so the two agree.
     * <p>
     * The chunk sits after the data sectors, delimited by the last two entries
     * of the sector offset table, and is neither encrypted nor keyed even in an
     * encrypted file: StormLib loads it with key 0. It <em>is</em> zlib
     * compressed when that makes it smaller.
     *
     * @return one checksum per data sector, or an empty array when the file
     *         records none or verification is off.
     * @throws JMpqException if the chunk is present but its bounds or length are
     *         structurally impossible. Absent is fine; unreadable is not, since
     *         the caller asked for these bytes to be checked.
     */
    private int[] readSectorChecksums(MpqFileEntry entry, int[] offsets, long base)
        throws IOException {
        if (!verifyChecksums || !entry.has(MpqFileEntry.FLAG_SECTOR_CRC)) {
            return new int[0];
        }
        final int sectors = dataSectorCount(entry);
        // The chunk is delimited by the two entries past the data sectors.
        final int start = offsets[sectors];
        final int end = offsets[sectors + 1];
        final int plainSize = sectors * 4;

        if (start < 0 || end < start || end > entry.compressedSize()) {
            // Not "no checksums" -- a checksum chunk the offset table cannot
            // locate means the table is damaged, and the data sectors it also
            // delimits are only accidentally still in range. Skipping quietly
            // would hand back a file that was asked to be verified and was not,
            // which is the one thing verification must never do.
            throw new JMpqException("The checksum chunk of <" + entry.name() + "> spans ["
                + start + ", " + end + "), outside its " + entry.compressedSize()
                + " stored bytes; the sector offset table is damaged.");
        }
        if (end == start) {
            // An empty chunk is the legitimate case: a file may carry the flag
            // and record nothing, which StormLib treats as nothing to check.
            return new int[0];
        }

        byte[] chunk = source.bytes(base + start, end - start);
        if (chunk.length < plainSize) {
            chunk = CompressionUtil.decompress(chunk, chunk.length, plainSize,
                header.formatVersion());
        }
        if (chunk.length < plainSize) {
            // Same reasoning as above: a chunk too short to hold one checksum
            // per sector is corrupt, not absent.
            throw new JMpqException("The checksum chunk of <" + entry.name() + "> holds "
                + chunk.length + " bytes but the file has " + sectors + " sectors, needing "
                + plainSize + ".");
        }

        final ByteBuffer in = ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        final int[] checksums = new int[sectors];
        for (int i = 0; i < sectors; i++) {
            checksums[i] = in.getInt();
        }
        return checksums;
    }

    /**
     * Compares one sector against its recorded checksum.
     * <p>
     * Zero and {@code 0xFFFFFFFF} mean "not recorded" - StormLib skips both
     * explicitly - so neither is a mismatch.
     */
    private void verifyChecksum(MpqFileEntry entry, int[] checksums, int index, byte[] sector)
        throws JMpqException {
        if (index >= checksums.length) {
            return;
        }
        final int expected = checksums[index];
        if (expected == 0 || expected == -1) {
            return;
        }
        final int actual = MpqChecksums.adler32(sector);
        if (actual != expected) {
            throw new JMpqException("Sector " + index + " of <" + entry.name()
                + "> has checksum 0x" + Integer.toHexString(actual) + " but the archive records 0x"
                + Integer.toHexString(expected) + "; the file is damaged.");
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

    /**
     * The file's stored bytes with any encryption removed, ready to be copied
     * into another archive verbatim.
     * <p>
     * Only valid when the target archive keeps this archive's sector size: a
     * sector offset table is expressed in the archive's sector size, so copying
     * these bytes into an archive with a different one produces a file no
     * reader can decode. {@code MpqArchiveWriter} enforces that.
     *
     * @param entry the file to copy.
     * @return exactly {@link MpqFileEntry#compressedSize()} bytes, decrypted.
     * @throws IOException if the data is damaged.
     */
    byte[] storedBytesDecrypted(MpqFileEntry entry) throws IOException {
        final long base = header.headerOffset() + entry.filePosition();
        final byte[] stored = source.bytes(base, entry.compressedSize());
        if (!entry.isEncrypted() || stored.length == 0) {
            return stored;
        }

        final int key = MpqNames.sectorKey(entry.name(), entry.flags(),
            entry.filePosition(), entry.normalSize());

        if (entry.has(MpqFileEntry.FLAG_SINGLE_UNIT)) {
            // One contiguous blob: a single key for the whole thing.
            new MPQEncryption(key, true).processSingle(ByteBuffer.wrap(stored));
            return stored;
        }

        if (!entry.hasSectorOffsetTable()) {
            // Stored without compression: no offset table, but still sectored,
            // so each sector has its own key. Decrypting the whole block here
            // wrote a correct first sector and corrupt ones after it, and the
            // caller then clears the encryption flags, which made the
            // corruption permanent in the rebuilt archive.
            final int sectorSize = header.sectorSize();
            for (int i = 0, offset = 0; offset < stored.length; i++, offset += sectorSize) {
                final int length = Math.min(sectorSize, stored.length - offset);
                final byte[] sector = new byte[length];
                System.arraycopy(stored, offset, sector, 0, length);
                new MPQEncryption(key + i, true).processSingle(ByteBuffer.wrap(sector));
                System.arraycopy(sector, 0, stored, offset, length);
            }
            return stored;
        }

        // Each chunk has its own key, so decrypt chunk by chunk using the
        // offset table's own boundaries. Only the data sectors: the checksum
        // chunk of a SECTOR_CRC file is never encrypted - StormLib loads it
        // with key 0 and writes it without encrypting - so "decrypting" it
        // would corrupt it, and the caller then clears the encryption flags,
        // which would make that permanent.
        final int[] offsets = readSectorOffsets(entry, base, key);
        final byte[] table = source.bytes(base, offsets.length * 4);
        new MPQEncryption(key - 1, true).processSingle(ByteBuffer.wrap(table));
        System.arraycopy(table, 0, stored, 0, table.length);

        for (int i = 0; i < dataSectorCount(entry); i++) {
            final int start = offsets[i];
            final int end = offsets[i + 1];
            if (start < 0 || end < start || end > stored.length) {
                throw new JMpqException("Sector " + i + " of <" + entry.name() + "> spans ["
                    + start + ", " + end + "), outside its " + stored.length + " stored bytes.");
            }
            final byte[] chunk = new byte[end - start];
            System.arraycopy(stored, start, chunk, 0, chunk.length);
            new MPQEncryption(key + i, true).processSingle(ByteBuffer.wrap(chunk));
            System.arraycopy(chunk, 0, stored, start, chunk.length);
        }
        return stored;
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
