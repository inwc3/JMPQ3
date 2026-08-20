package org.inwc3.jmpq;

import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.nio.ByteBuffer;

/**
 * Encodes one file's content into an archive image as sectors.
 * <p>
 * The layout produced is a sector offset table followed by the sectors it
 * describes. Each sector is compressed independently; a sector that does not
 * shrink is stored verbatim, which a reader detects because its stored length
 * equals its natural length.
 */
final class MpqSectorWriter {
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
        final int tableBytes = (dataSectors + 1) * 4;

        // Worst case is the offset table plus every sector stored verbatim plus
        // one type byte each. Nothing this encoder produces can exceed it,
        // because a sector that does not shrink is stored raw. The pre-2.0
        // writer guessed content.length * 2 and mapped that much file, which
        // overflowed for incompressible input.
        final long worstCase = (long) tableBytes + content.length + dataSectors;
        if (worstCase > MpqImageBuffer.MAX_SIZE) {
            throw new IllegalArgumentException("File <" + name + "> is too large for an in-memory"
                + " build: " + content.length + " bytes would need " + worstCase + " of staging.");
        }

        final boolean encrypt = (flags & MpqFileEntry.FLAG_ENCRYPTED) == MpqFileEntry.FLAG_ENCRYPTED;
        final int baseKey = MpqNames.sectorKey(name, flags, filePosition, content.length);

        final ByteBuffer region = image.reserve((int) worstCase);
        final int[] offsets = new int[dataSectors + 1];
        offsets[0] = tableBytes;

        region.position(tableBytes);
        for (int i = 0; i < dataSectors; i++) {
            final int from = i * sectorSize;
            final int length = Math.min(sectorSize, content.length - from);
            final byte[] raw = new byte[length];
            System.arraycopy(content, from, raw, 0, length);

            final byte[] payload = encodeSector(raw, recompress);
            if (encrypt) {
                new MPQEncryption(baseKey + i, false).processSingle(ByteBuffer.wrap(payload));
            }
            region.put(payload);
            offsets[i + 1] = offsets[i] + payload.length;
        }

        final int compressedSize = offsets[dataSectors];

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
     * @param contentLength the file's decoded size.
     * @return the flags a newly encoded file should carry.
     */
    static int flagsFor(int contentLength) {
        return contentLength == 0
            ? MpqFileEntry.FLAG_EXISTS
            : MpqFileEntry.FLAG_EXISTS | MpqFileEntry.FLAG_COMPRESSED;
    }
}
