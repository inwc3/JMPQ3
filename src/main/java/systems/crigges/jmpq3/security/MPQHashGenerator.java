package systems.crigges.jmpq3.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * MPQ cryptographic hashing function. Generates a 32 bit hash from the supplied
 * data using the specified cryptographic lookup table.
 * <p>
 * New generators are created using the static constructor methods. There are 4
 * different types of hash generator available for use with different parts of
 * MPQ.
 */
public class MPQHashGenerator {
    /**
     * Seed 1 used as hash result.
     */
    private int seed1;

    /**
     * Seed 2 used internally.
     */
    private int seed2;

    /**
     * The cryptographic lookup table used to generate the hash.
     */
    private final CryptographicLUT lut;

    /**
     * Constructs a hash generator using the given cryptographic LUT.
     * <p>
     * The hash generator can be used immediately.
     * 
     * @param lut
     *            cryptographic LUT used to generate hashes.
     */
    private MPQHashGenerator(CryptographicLUT lut) {
        reset();
        this.lut = lut;
    }

    /**
     * Reset the hash generator state. After a call to this method the hash
     * generator will behave as if it was freshly created.
     */
    public void reset() {
        seed1 = 0x7FED7FED;
        seed2 = 0xEEEEEEEE;
    }

    /**
     * Convenience method to process data from the given string, assuming UTF_8
     * encoding.
     * 
     * @param src
     *            string to be hashed.
     */
    public void process(String src) {
        process(ByteBuffer.wrap(src.toUpperCase().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Processes data from the given buffer.
     * <p>
     * Calling this method multiple times on different data sets produces the
     * same resulting hash as calling it once with a data set produced by
     * concatenating the separate data sets together in call order.
     * 
     * @param src
     *            data to be hashed.
     */
    public void process(ByteBuffer src) {
        while (src.hasRemaining()) {
            final byte value = src.get();
            seed1 = lut.lookup(value) ^ (seed1 + seed2);
            seed2 = Byte.toUnsignedInt(value) + seed1 + seed2 + (seed2 << 5) + 3;
        }
    }

    /**
     * Get the resulting hash for the processed input.
     * 
     * @return 32 bit hash.
     */
    public int getHash() {
        return seed1;
    }

    /**
     * Create a new hash generator for hashtable bucket array index hashes.
     * 
     * @return new hash generator.
     */
    public static MPQHashGenerator getTableOffsetGenerator() {
        return new MPQHashGenerator(CryptographicLUT.HASH_TABLE_OFFSET);
    }

    /**
     * Create a new hash generator for part 1 of hashtable keys.
     * 
     * @return new hash generator.
     */
    public static MPQHashGenerator getTableKey1Generator() {
        return new MPQHashGenerator(CryptographicLUT.HASH_TABLE_KEY1);
    }

    /**
     * Create a new hash generator for part 2 of hashtable keys.
     * 
     * @return new hash generator.
     */
    public static MPQHashGenerator getTableKey2Generator() {
        return new MPQHashGenerator(CryptographicLUT.HASH_TABLE_KEY2);
    }

    /**
     * Create a new hash generator for MPQ encryption keys.
     * 
     * @return new hash generator.
     */
    public static MPQHashGenerator getFileKeyGenerator() {
        return new MPQHashGenerator(CryptographicLUT.HASH_ENCRYPTION_KEY);
    }
}
