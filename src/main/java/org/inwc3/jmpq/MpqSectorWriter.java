package org.inwc3.jmpq;

import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.nio.ByteBuffer;

/**
 * Encodes one file's content into an archive image as sectors.
 * <p>
 * Public only so the deprecated {@code MpqFile.writeFileAndBlock} overloads can
 * delegate here rather than carrying a second copy of the encoder. New code
 * should use {@link MpqArchiveWriter}.
 * <p>
 * The layout produced is a sector offset table followed by the sectors it
 * describes. Each sector is compressed independently; a sector that does not
 * shrink is stored verbatim, which a reader detects because its stored length
 * equals its natural length.
 */
public final class MpqSectorWriter {
    /** Compression-type byte for deflate, the only codec this writer emits. */
    private static final byte TYPE_DEFLATE = 0x02;

    private MpqSectorWriter() {
    }

    /**
     * Encodes a file and appends it to the image.
     *
     * @param image        destination; the file is appended at its current
     *                     position.
     * @param content      the file's bytes.
     * @param sectorSize   the archive's sector size.
     * @param name         the file's path, used to derive the encryption key.
     * @param flags        block flags; only the encryption bits affect encoding.
     * @param filePosition the file's offset relative to the archive header,
     *                     needed for an adjusted key.
     * @param recompress   compression strategy.
     * @return the number of bytes written, which is the block's compressed size.
     */
    static int write(MpqImageBuffer image, byte[] content, int sectorSize, String name,
                     int flags, long filePosition, RecompressOptions recompress) {
        if (content.length == 0) {
            return 0;
        }

        final int dataSectors = MpqFileReader.sectorCount(content.length, sectorSize);
        final boolean checksums =
            (flags & MpqFileEntry.FLAG_SECTOR_CRC) == MpqFileEntry.FLAG_SECTOR_CRC;
        // A SECTOR_CRC file needs one more offset entry, delimiting the
        // checksum chunk that follows the data sectors.
        final int offsetEntries = dataSectors + 1 + (checksums ? 1 : 0);
        final int tableBytes = offsetEntries * 4;

        // Worst case is the offset table plus every sector stored verbatim plus
        // one type byte each, plus an uncompressed checksum chunk. Nothing this
        // encoder produces can exceed it, because a sector that does not shrink
        // is stored raw. The pre-2.0 writer guessed content.length * 2 and
        // mapped that much file, which overflowed for incompressible input.
        final long worstCase = (long) tableBytes + content.length + dataSectors
            + (checksums ? 4L * dataSectors : 0);
        if (worstCase > MpqImageBuffer.MAX_SIZE) {
            throw new IllegalArgumentException("File <" + name + "> is too large for an in-memory"
                + " build: " + content.length + " bytes would need " + worstCase + " of staging.");
        }

        final boolean encrypt = (flags & MpqFileEntry.FLAG_ENCRYPTED) == MpqFileEntry.FLAG_ENCRYPTED;
        final int baseKey = MpqNames.sectorKey(name, flags, filePosition, content.length);

        final ByteBuffer region = image.reserve((int) worstCase);
        final int[] offsets = new int[offsetEntries];
        final int[] adler = new int[checksums ? dataSectors : 0];
        offsets[0] = tableBytes;

        region.position(tableBytes);
        for (int i = 0; i < dataSectors; i++) {
            final int from = i * sectorSize;
            final int length = Math.min(sectorSize, content.length - from);
            final byte[] raw = new byte[length];
            System.arraycopy(content, from, raw, 0, length);

            final byte[] payload = encodeSector(raw, recompress);
            if (checksums) {
                // Over the stored sector before encrypting it, which is the
                // same bytes a reader sees after decrypting. StormLib takes the
                // checksum at exactly these two points.
                adler[i] = MpqChecksums.adler32(payload);
            }
            if (encrypt) {
                new MPQEncryption(baseKey + i, false).processSingle(ByteBuffer.wrap(payload));
            }
            region.put(payload);
            offsets[i + 1] = offsets[i] + payload.length;
        }

        if (checksums) {
            // Zlib compressed when that is smaller, and never encrypted: on the
            // read side StormLib loads this chunk with key 0.
            final byte[] chunk = encodeChecksums(adler, recompress);
            region.put(chunk);
            offsets[dataSectors + 1] = offsets[dataSectors] + chunk.length;
        }

        final int compressedSize = offsets[offsetEntries - 1];

        // Fill in the offset table now that the sizes are known.
        final ByteBuffer table = ByteBuffer.allocate(tableBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int offset : offsets) {
            table.putInt(offset);
        }
        final byte[] tableBytesOut = table.array();
        if (encrypt) {
            // The offset table uses the key one below the first sector's.
            new MPQEncryption(baseKey - 1, false).processSingle(ByteBuffer.wrap(tableBytesOut));
        }
        region.position(0);
        region.put(tableBytesOut);

        image.advance(compressedSize);
        return compressedSize;
    }

    /**
     * Encodes the per-sector checksum chunk of a {@code SECTOR_CRC} file.
     * <p>
     * The chunk is a plain array of little-endian Adler-32 values, one per data
     * sector, zlib compressed when that is smaller. A reader detects the
     * compression the same way it does for a sector: by the stored length being
     * shorter than the natural one.
     *
     * @param adler      one checksum per data sector.
     * @param recompress compression strategy.
     * @return the chunk as stored.
     */
    private static byte[] encodeChecksums(int[] adler, RecompressOptions recompress) {
        final ByteBuffer plain = ByteBuffer.allocate(adler.length * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int value : adler) {
            plain.putInt(value);
        }
        return encodeSector(plain.array(), recompress);
    }

    /**
     * @return the sector's stored form: a deflate type byte followed by
     *         compressed data, or the raw bytes when compressing does not pay.
     */
    private static byte[] encodeSector(byte[] raw, RecompressOptions recompress) {
        byte[] compressed = null;
        try {
            compressed = CompressionUtil.compress(raw, recompress);
        } catch (ArrayIndexOutOfBoundsException ignored) {
            // The codec could not handle this input; store it instead.
        }
        if (compressed == null || compressed.length + 1 >= raw.length) {
            // Storing it keeps the sector's length equal to its natural length,
            // which is exactly how a reader knows there is no type byte.
            return raw;
        }
        final byte[] payload = new byte[compressed.length + 1];
        payload[0] = TYPE_DEFLATE;
        System.arraycopy(compressed, 0, payload, 1, compressed.length);
        return payload;
    }

    /**
     * Encodes a file into a caller-supplied buffer.
     * <p>
     * Exists for the deprecated {@code MpqFile.writeFileAndBlock} overloads,
     * whose signatures take a {@link ByteBuffer}. Encoding happens here so
     * there is still only one implementation of it.
     *
     * @param target       destination, positioned where the file data starts.
     * @param content      the file's bytes.
     * @param sectorSize   the archive's sector size.
     * @param name         the file's path, for the encryption key.
     * @param flags        block flags.
     * @param filePosition the file's offset relative to the archive header.
     * @param recompress   compression strategy.
     * @return the number of bytes written.
     */
    public static int writeInto(ByteBuffer target, byte[] content, int sectorSize, String name,
                                int flags, long filePosition, RecompressOptions recompress) {
        final MpqImageBuffer staging = new MpqImageBuffer(Math.max(64, content.length + 64));
        final int written = write(staging, content, sectorSize, name, flags, filePosition, recompress);
        target.put(staging.toByteArray(), 0, written);
        return written;
    }

    /**
     * @param contentLength the file's decoded size.
     * @param checksums     whether to record a per-sector checksum.
     * @return the flags a newly encoded file should carry.
     */
    static int flagsFor(int contentLength, boolean checksums) {
        if (contentLength == 0) {
            // An empty file has no sectors, so it can carry no checksums.
            return MpqFileEntry.FLAG_EXISTS;
        }
        return MpqFileEntry.FLAG_EXISTS | MpqFileEntry.FLAG_COMPRESSED
            | (checksums ? MpqFileEntry.FLAG_SECTOR_CRC : 0);
    }
}
