package systems.crigges.jmpq3.compression;

import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;

/**
 * Created by Frotty on 30.04.2017.
 */
public class CompressionUtil {
    private static final ADPCM ADPCM = new ADPCM(2);
    private static final Huffman huffman = new Huffman();
    private static final ZopfliHelper zopfli = new ZopfliHelper();
    /* Masks for Decompression Type 2 */
    private static final byte FLAG_HUFFMAN = 0x01;
    public static final byte FLAG_DEFLATE = 0x02;
    // 0x04 is unknown
    private static final byte FLAG_IMPLODE = 0x08;
    private static final byte FLAG_BZIP2 = 0x10;
    private static final byte FLAG_SPARSE = 0x20;
    private static final byte FLAG_ADPCM1C = 0x40;
    private static final byte FLAG_ADPCM2C = -0x80;

    public static byte[] compress(byte[] temp, boolean recompress) {
        byte[] zopfliA = zopfli.deflate(temp);
        byte[] jzlib = JzLibHelper.deflate(temp);
        return recompress ? zopfliA : jzlib;
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
            if (((compressionType & FLAG_BZIP2) != 0)) {
                throw new JMpqException("Unsupported compression bzip2");
            }
            if (((compressionType & FLAG_IMPLODE) != 0)) {
                throw new JMpqException("Unsupported compression pkware");
            }
            if (((compressionType & FLAG_SPARSE) != 0)) {
                throw new JMpqException("Unsupported compression sparse");
            }
            if (((compressionType & FLAG_DEFLATE) != 0)) {
                out.put(JzLibHelper.inflate(sector, 1, uncompressedSize));
                out.position(0);
                flip = !flip;
            }
            if (((compressionType & FLAG_HUFFMAN) != 0)) {
                (flip ? in : out).clear();
                huffman.Decompress(flip ? out : in, flip ? in : out);
                out.limit(out.position());
                in.position(0);
                out.position(0);
                flip = !flip;
            }
            if (((compressionType & FLAG_ADPCM2C) != 0)) {
                ByteBuffer newOut = ByteBuffer.wrap(new byte[uncompressedSize]);
                ADPCM.decompress(flip ? out : in, newOut, 2);
                (flip ? out : in).position(0);
                return newOut.array();
            }
            if (((compressionType & FLAG_ADPCM1C) != 0)) {
                ByteBuffer newOut = ByteBuffer.wrap(new byte[uncompressedSize]);
                ADPCM.decompress(flip ? out : in, newOut, 1);
                (flip ? out : in).position(0);
                return newOut.array();
            }
            return (flip ? out : in).array();
        }
    }
}
