package systems.crigges.jmpq3.compression;

/**
 * The single-algorithm compression masks that can appear in the leading
 * compression-type byte of a compressed MPQ sector.
 * <p>
 * <b>Declaration order is load bearing.</b> It mirrors StormLib's
 * {@code dcmp_table} in {@code SCompression.cpp}, and StormLib applies the
 * decompressors in exactly that table order when a sector carries more than one
 * mask bit. Reordering these constants silently changes how multi-compressed
 * sectors (most notably {@code ADPCM|HUFFMAN} WAV sectors, and
 * {@code SPARSE|ZLIB}) are decoded.
 * <p>
 * Note that {@code 0x12} is <em>not</em> in this enum. StormLib defines
 * {@code MPQ_COMPRESSION_LZMA == 0x12} as a standalone value rather than a
 * combination of flags, and it is only recognised for archives of format
 * version 2 and above. In a version 0 or 1 archive the very same byte means
 * {@code BZIP2 | ZLIB}. See {@link CompressionUtil} for how the two dispatch
 * models are kept apart, and {@code docs/mpq-format-notes.md} for the rationale.
 */
public enum CompressionType {
    /** BZIP2, added in Warcraft III. */
    BZIP2(0x10),
    /** PKWARE Data Compression Library ("implode"). */
    PKWARE(0x08),
    /** Deflate/zlib. */
    ZLIB(0x02),
    /** Huffman, in practice only ever paired with ADPCM on WAV data. */
    HUFFMAN(0x01),
    /** IMA ADPCM, two channels. */
    ADPCM_STEREO(0x80),
    /** IMA ADPCM, one channel. */
    ADPCM_MONO(0x40),
    /** Run-length ("sparse") compression, added in Starcraft II. */
    SPARSE(0x20);

    /**
     * Standalone LZMA marker, valid only for format version 2 and above.
     * <p>
     * Deliberately not an enum constant: it overlaps {@code BZIP2 | ZLIB} and
     * must never take part in mask-based dispatch.
     */
    public static final int MASK_LZMA = 0x12;

    private final int mask;

    CompressionType(int mask) {
        this.mask = mask;
    }

    /**
     * @return the bit this algorithm occupies in a sector's compression-type
     *         byte.
     */
    public int mask() {
        return mask;
    }
}
