package systems.crigges.jmpq3.compression;

import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;

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

    public static byte[] compress(byte[] temp, RecompressOptions recompress) {
        if (recompress.recompress && recompress.useZopfli && zopfli == null) {
            zopfli = new ZopfliHelper();
        }
        return recompress.useZopfli ? zopfli.deflate(temp, recompress.iterations) : JzLibHelper.deflate(temp);
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
