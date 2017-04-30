package systems.crigges.jmpq3.compression;

import systems.crigges.jmpq3.JMpqException;

import java.nio.ByteBuffer;

/**
 * Created by Frotty on 30.04.2017.
 */
public class CompressionUtil {
    private static final ADPCM ADPCM = new ADPCM(2);
    private static final Huffman huffman = new Huffman();

    public static void compress() {

    }

    public static byte[] decompress(byte[] sector, int compressedSize, int uncompressedSize) throws JMpqException {
        if (compressedSize == uncompressedSize) {
            return sector;
        } else {
            byte compressionType = sector[0];
            if (((compressionType & 1) == 1)) {
                ByteBuffer out = ByteBuffer.wrap(new byte[uncompressedSize]);
                ByteBuffer in = ByteBuffer.wrap(sector);
                in.position(1);
                huffman.Decompress(in, out);
                return out.array();
            } else if (((compressionType & 2) == 2)) {
                return JzLibHelper.inflate(sector, 1, uncompressedSize);
            } else if (((compressionType & 8) == 8)) {
                throw new JMpqException("Unsupported compression pkware");
            } else if (((compressionType & 16) == 16)) {
                throw new JMpqException("Unsupported compression bzip2");
            } else if (((compressionType & 64) == 64)) {
                ByteBuffer out = ByteBuffer.wrap(new byte[uncompressedSize]);
                ByteBuffer in = ByteBuffer.wrap(sector);
                in.position(1);
                ADPCM.decompress(in, out, 1);
                return out.array();
            } else if (((compressionType & 128) == 128)) {
                ByteBuffer out = ByteBuffer.wrap(new byte[uncompressedSize]);
                ByteBuffer in = ByteBuffer.wrap(sector);
                in.position(1);
                ADPCM.decompress(in, out, 2);
                return out.array();
            }
            throw new JMpqException("Unknown compression algorithm");
        }
    }
}
