package systems.crigges.jmpq3.security;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MPQ encryption processor.
 * <p>
 * MPQ files use a hybrid stream and block based encryption algorithm. The
 * blocks are exclusive-ored with the input data to produce encrypted data.
 * Blocks are generated from the key and a seed. The seed is modified by the
 * normal data. Any remaining data that cannot fit inside a block at the end is
 * left unchanged.
 * <p>
 * The encryption is not secure and suffers from a similar weakness to that of
 * the Enigma code. If one knows 4 aligned bytes of a file, eg the starting 4
 * character, then one can brute force the key by trying all keys until one is
 * found that works. As keys are only 32 bits (4 bytes) long this process will
 * take at most a few minutes on a modern PC. It is likely this algorithm was
 * created more to obfuscate data rather than protect it and as such should
 * never be used for any security critical application.
 */
public class MPQEncryption {
    /**
     * Size of a encryption block.
     */
    private static final int BLOCK_SIZE = 4;

    /**
     * Internal seed used by algorithm.
     */
    private int seed;

    /**
     * The used cryptographic key, subject to mutation between blocks.
     */
    private int key;

    /**
     * Direction of algorithm to use. Encrypting or decrypting.
     */
    private boolean decrypt;

    /**
     * Construct from the provided encryption key for the specified direction.
     * <p>
     * Data processed with invert as false can be be restored by processing with
     * invert as true. The opposite also will work.
     * 
     * @param key
     *            cryptographic key to use.
     * @param invert
     *            if inverse algorithm should be used.
     */
    public MPQEncryption(int key, boolean invert) {
        changeKey(key, invert);
    }

    /**
     * Changes the currently used cryptographic key, reseting internal state in
     * the process.
     * <p>
     * After this method is called the state of the object is completely reset,
     * as if the object was freshly created. All process methods will also
     * behave as if this object was freshly created.
     * <p>
     * Data processed with invert as false can be be restored by processing with
     * invert as true. The opposite also will work.
     * 
     * @param key
     *            cryptographic key to use.
     * @param invert
     *            if inverse algorithm should be used.
     */
    public void changeKey(int key, boolean invert) {
        this.key = key;
        decrypt = invert;
        seed = 0xEEEEEEEE;
    }

    /**
     * Processes the source buffer into the destination buffer.
     * <p>
     * If true is returned then the source buffer has been depleted and was not
     * enough to fill the destination buffer. If false is returned then the
     * destination buffer can be considered full of data, even if hasRemaining
     * returns true.
     * <p>
     * If true is returned but there is no more source data to process then
     * processFinal must be called to assure all remaining data is fully
     * processed.
     * 
     * @param src
     *            source buffer.
     * @param dst
     *            destination buffer.
     * @return true if more source data is required to fill the destination
     *         buffer, false if the destination buffer can be considered full.
     */
    public boolean process(final ByteBuffer src, final ByteBuffer dst) {
        // configure endian
        src.order(ByteOrder.LITTLE_ENDIAN);
        dst.order(ByteOrder.LITTLE_ENDIAN);

        // process as many blocks as possible
        for (int blocks = Math.min(src.remaining(), dst.remaining()) / BLOCK_SIZE; blocks > 0; blocks--) {
            // prepare block
            seed += CryptographicLUT.ENCRYPTION.lookup((byte) key);
            final int block = key + seed;

            // process input
            final int in = src.getInt();
            final int out = in ^ block;
            dst.putInt(out);

            // advance algorithm
            seed += (decrypt ? out : in) + (seed << 5) + 3;
            key = (~key << 21) + 0x11111111 | key >>> 11;
        }

        // enforce block size processing
        return dst.remaining() >= BLOCK_SIZE;
    }

    /**
     * Processes the source buffer into the destination buffer. The source
     * buffer is considered to have all data left to process.
     * <p>
     * If true is returned then the destination buffer can be considered full,
     * even if hasRemaining returns true. If false is returned then the source
     * buffer has been fully depleted and nothing more will be written to the
     * destination buffer.
     * <p>
     * It is valid to call this method again if true is returned. The behaviour
     * of the process method is not defined after a call to this method.
     * 
     * @param src
     *            source buffer.
     * @param dst
     *            destination buffer.
     * @return true if the destination buffer can be considered full, false if
     *         all data has been written.
     */
    public boolean processFinal(final ByteBuffer src, final ByteBuffer dst) {
        process(src, dst);

        // any data that cannot fit into a block must be written out
        if (src.remaining() < BLOCK_SIZE) {
            while (src.hasRemaining() && dst.hasRemaining()) {
                dst.put(src.get());
            }
        }

        // enforce full dump of src
        return src.hasRemaining();
    }

    /**
     * Process all data inside a single buffer, writing results back to the same
     * buffer. The behaviour of this method is defined only for when no previous
     * calls to process and processFinal have been made.
     * <p>
     * Only some algorithms support this method.
     * 
     * @param buff
     *            source and destination buffer.
     */
    public void processSingle(final ByteBuffer buff) {
        final ByteBuffer dst = buff.duplicate();
        processFinal(buff, dst);
    }
}
