package systems.crigges.jmpq3.compression;

/**
 * Emits a zlib stream made entirely of stored (uncompressed) deflate blocks.
 * <p>
 * This is what {@code RecompressOptions.recompress == false} produces: the
 * sector is wrapped so it decodes as valid zlib without spending any time on
 * entropy coding. It replaces a jzlib level-0 round trip, which produced the
 * same bytes far more slowly.
 * <p>
 * Stateless and safe for concurrent use. The previous implementation kept a
 * {@link ThreadLocal} scratch buffer; sizing the output exactly is both simpler
 * and cheaper than growing and caching a shared one.
 */
final class ZlibStore {
    /** Maximum payload of a single stored deflate block. */
    private static final int MAX_BLOCK = 0xFFFF;

    /** Largest number of bytes that can be summed before Adler-32 overflows. */
    private static final int ADLER_CHUNK = 5552;

    private static final int ADLER_MODULUS = 65521;

    private ZlibStore() {
    }

    /**
     * @param in bytes to wrap.
     * @return a zlib stream that decodes back to {@code in}.
     */
    static byte[] storeLevel0(byte[] in) {
        final int len = in.length;
        // long arithmetic: len + MAX_BLOCK - 1 overflows for an input within
        // 64 KiB of Integer.MAX_VALUE.
        final int blocks = (int) Math.max(1, ((long) len + MAX_BLOCK - 1) / MAX_BLOCK);
        // 2 byte zlib header + 5 byte header per stored block + 4 byte Adler-32.
        final byte[] out = new byte[2 + len + blocks * 5 + 4];

        int o = 0;
        out[o++] = 0x78;
        out[o++] = 0x01;

        int off = 0;
        do {
            final int blockLen = Math.min(MAX_BLOCK, len - off);
            final boolean last = (off + blockLen) == len;

            out[o++] = (byte) (last ? 0x01 : 0x00);
            out[o++] = (byte) (blockLen & 0xFF);
            out[o++] = (byte) ((blockLen >>> 8) & 0xFF);
            final int nlen = (~blockLen) & 0xFFFF;
            out[o++] = (byte) (nlen & 0xFF);
            out[o++] = (byte) ((nlen >>> 8) & 0xFF);

            System.arraycopy(in, off, out, o, blockLen);
            o += blockLen;
            off += blockLen;
            // A zero length input still needs one (empty, final) block.
        } while (off < len);

        final int adler = adler32(in);
        out[o++] = (byte) ((adler >>> 24) & 0xFF);
        out[o++] = (byte) ((adler >>> 16) & 0xFF);
        out[o++] = (byte) ((adler >>> 8) & 0xFF);
        out[o] = (byte) (adler & 0xFF);

        return out;
    }

    private static int adler32(byte[] in) {
        int s1 = 1;
        int s2 = 0;
        int i = 0;
        while (i < in.length) {
            final int end = i + Math.min(ADLER_CHUNK, in.length - i);
            while (i < end) {
                s1 += in[i++] & 0xFF;
                s2 += s1;
            }
            s1 %= ADLER_MODULUS;
            s2 %= ADLER_MODULUS;
        }
        return (s2 << 16) | s1;
    }
}
