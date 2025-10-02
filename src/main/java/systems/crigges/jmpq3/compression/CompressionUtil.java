package systems.crigges.jmpq3.compression;

import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by Frotty on 30.04.2017.
 */
public class CompressionUtil {
    private static ADPCM ADPCM;
    private static Huffman huffman;
    private static ZopfliHelper zopfli;
    /* Masks for Decompression Type 2 */
    private static final byte FLAG_HUFFMAN = 0x01;
    public static final byte FLAG_DEFLATE = 0x02;
    // 0x04 is unknown
    private static final byte FLAG_IMPLODE = 0x08;
    private static final byte FLAG_BZIP2 = 0x10;
    private static final byte FLAG_SPARSE = 0x20;
    private static final byte FLAG_ADPCM1C = 0x40;
    private static final byte FLAG_ADPCM2C = -0x80;
    private static final byte FLAG_LMZA = 0x12;
    private static final ThreadLocal<ByteBuffer> STORE_BUFFER =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(70000));

    /**
     * Optimized level-0 zlib compression (stored blocks only).
     * Uses direct ByteBuffer for better performance and reduces allocations.
     */
    private static byte[] zlibStoreLevel0(byte[] in) {
        int len = in.length;
        int blocks = (len + 65534) / 65535;
        int outCap = 2 + len + blocks * 5 + 4;

        // Get thread-local buffer and ensure capacity
        ByteBuffer buffer = STORE_BUFFER.get();
        if (buffer.capacity() < outCap) {
            buffer = ByteBuffer.allocate(Math.max(outCap, buffer.capacity() * 2));
            STORE_BUFFER.set(buffer);
        }

        buffer.clear();
        buffer.limit(outCap);

        // Pre-compute Adler-32 in a single pass (optimized modulo operations)
        int s1 = 1, s2 = 0;
        int i = 0;

        // Process in chunks to reduce modulo operations (major optimization)
        while (i < len) {
            int chunk = Math.min(5552, len - i); // 5552 is max before overflow
            int end = i + chunk;

            while (i < end) {
                s1 += (in[i++] & 0xFF);
                s2 += s1;
            }

            s1 %= 65521;
            s2 %= 65521;
        }

        int adler = (s2 << 16) | s1;

        // Write zlib header (CMF and FLG)
        buffer.put((byte) 0x78);
        buffer.put((byte) 0x01);

        // Write stored blocks
        int off = 0;
        while (off < len) {
            int blockLen = Math.min(65535, len - off);
            boolean finalBlock = (off + blockLen) == len;

            // Block header
            buffer.put((byte) (finalBlock ? 0x01 : 0x00));

            // LEN (little-endian)
            buffer.put((byte) (blockLen & 0xFF));
            buffer.put((byte) ((blockLen >>> 8) & 0xFF));

            // NLEN (one's complement of LEN, little-endian)
            int nlen = (~blockLen) & 0xFFFF;
            buffer.put((byte) (nlen & 0xFF));
            buffer.put((byte) ((nlen >>> 8) & 0xFF));

            // Block data
            buffer.put(in, off, blockLen);
            off += blockLen;
        }

        // Write Adler-32 checksum (big-endian)
        buffer.put((byte) ((adler >>> 24) & 0xFF));
        buffer.put((byte) ((adler >>> 16) & 0xFF));
        buffer.put((byte) ((adler >>> 8) & 0xFF));
        buffer.put((byte) (adler & 0xFF));

        // Copy to output array
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);

        return out;
    }

    // Update your compress method to use the optimized version:
    public static byte[] compress(byte[] temp, RecompressOptions recompress) {
        if (!recompress.recompress) {
            return zlibStoreLevel0(temp); // Use the fastest version
        }
        if (recompress.recompress && recompress.useZopfli && zopfli == null) {
            zopfli = new ZopfliHelper();
        }
        return recompress.useZopfli ? zopfli.deflate(temp, recompress.iterations)
            : JzLibHelper.deflate(temp, recompress.recompress);
    }

    public static byte[] decompress(byte[] sector, int compressedSize, int uncompressedSize) throws JMpqException {
        if (compressedSize == uncompressedSize) {
            return sector;
        } else {
            byte compressionType = sector[0];
            ByteBuffer out = ByteBuffer.wrap(new byte[uncompressedSize]);
            ByteBuffer in = ByteBuffer.wrap(sector);
            in.position(1);

            boolean flip = false;
            boolean isLZMACompressed = (compressionType & FLAG_LMZA) != 0;
            boolean isBzip2Compressed = (compressionType & FLAG_BZIP2) != 0;
            boolean isImploded = (compressionType & FLAG_IMPLODE) != 0;
            boolean isSparseCompressed = (compressionType & FLAG_SPARSE) != 0;
            boolean isDeflated = (compressionType & FLAG_DEFLATE) != 0;
            boolean isHuffmanCompressed = (compressionType & FLAG_HUFFMAN) != 0;

            if (isDeflated) {
                out.put(JzLibHelper.inflate(sector, 1, uncompressedSize));
                out.position(0);
                flip = !flip;
            } else if (isLZMACompressed) {
                throw new JMpqException("Unsupported compression LZMA");
            } else if (isBzip2Compressed) {
                throw new JMpqException("Unsupported compression Bzip2");
            } else if (isImploded) {
                byte[] output = new byte[uncompressedSize];
                Exploder.pkexplode(sector, output, 1);
                out.put(output);
                out.position(0);
                flip = !flip;
            }
            if (isSparseCompressed) {
                throw new JMpqException("Unsupported compression sparse");
            }

            if (isHuffmanCompressed) {
                if (huffman == null) {
                    huffman = new Huffman();
                }
                (flip ? in : out).clear();
                huffman.Decompress(flip ? out : in, flip ? in : out);
                out.limit(out.position());
                in.position(0);
                out.position(0);
                flip = !flip;
            }
            if (((compressionType & FLAG_ADPCM2C) != 0)) {
                if (ADPCM == null) {
                    ADPCM = new ADPCM(2);
                }
                ByteBuffer newOut = ByteBuffer.wrap(new byte[uncompressedSize]);
                ADPCM.decompress(flip ? out : in, newOut, 2);
                (flip ? out : in).position(0);
                return newOut.array();
            }
            if (((compressionType & FLAG_ADPCM1C) != 0)) {
                if (ADPCM == null) {
                    ADPCM = new ADPCM(2);
                }
                ByteBuffer newOut = ByteBuffer.wrap(new byte[uncompressedSize]);
                ADPCM.decompress(flip ? out : in, newOut, 1);
                (flip ? out : in).position(0);
                return newOut.array();
            }
            return (flip ? out : in).array();
        }
    }

    public static byte[] explode(byte[] sector, int compressedSize, int uncompressedSize) throws JMpqException {
        if (compressedSize == uncompressedSize) {
            return sector;
        } else {
            ByteBuffer out = ByteBuffer.wrap(new byte[uncompressedSize]);

            byte[] output = new byte[uncompressedSize];
            Exploder.pkexplode(sector, output, 0);
            out.put(output);
            out.position(0);
            return out.array();
        }
    }
}
