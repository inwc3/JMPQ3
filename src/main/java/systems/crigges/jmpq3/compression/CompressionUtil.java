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

    static byte[] zlibStoreLevel0(byte[] in) {
        // zlib header for deflate, 32K window, "fastest" -> 0x78 0x01
        // (CMF=0x78, FLG chosen so (CMF*256+FLG)%31==0 and low 2 bits indicate level)
        int len = in.length;
        int blocks = (len + 65534) / 65535;
        int outCap = 2 + len + blocks * 5 + 4; // header + data + block headers + adler32
        byte[] out = new byte[outCap];
        int p = 0;
        out[p++] = (byte)0x78;
        out[p++] = (byte)0x01;

        int off = 0;
        while (off < len) {
            int n = Math.min(65535, len - off);
            boolean finalBlock = (off + n) == len;
            out[p++] = (byte)(finalBlock ? 0x01 : 0x00); // BFINAL=1 on last, BTYPE=00 (stored)
            // 2 bytes LEN (little endian), 2 bytes NLEN = ~LEN
            out[p++] = (byte)(n & 0xFF);
            out[p++] = (byte)((n >>> 8) & 0xFF);
            int nlen = (~n) & 0xFFFF;
            out[p++] = (byte)(nlen & 0xFF);
            out[p++] = (byte)((nlen >>> 8) & 0xFF);
            System.arraycopy(in, off, out, p, n);
            p += n;
            off += n;
        }

        // Adler-32
        long s1 = 1, s2 = 0;
        for (byte b : in) {
            s1 = (s1 + (b & 0xFF)) % 65521;
            s2 = (s2 + s1) % 65521;
        }
        int adler = (int)((s2 << 16) | s1);
        out[p++] = (byte)((adler >>> 24) & 0xFF);
        out[p++] = (byte)((adler >>> 16) & 0xFF);
        out[p++] = (byte)((adler >>> 8) & 0xFF);
        out[p++] = (byte)(adler & 0xFF);

        return (p == out.length) ? out : Arrays.copyOf(out, p);
    }


    public static byte[] compress(byte[] temp, RecompressOptions recompress) {
        if (!recompress.recompress) {
            return zlibStoreLevel0(temp);
        }
        if (recompress.recompress && recompress.useZopfli && zopfli == null) {
            zopfli = new ZopfliHelper();
        }
        return recompress.useZopfli ? zopfli.deflate(temp, recompress.iterations) : JzLibHelper.deflate(temp, recompress.recompress);
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
