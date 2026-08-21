package systems.crigges.jmpq3.compression;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.LZMAInputStream;
import systems.crigges.jmpq3.JMpqException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Sector level compression and decompression.
 * <p>
 * <b>Thread safety:</b> all methods on this class are stateless and safe to call
 * concurrently. Every codec that carries state ({@link Huffman},
 * {@link ADPCM}, the deflater and inflater in {@link JzLibHelper}) is
 * instantiated per call. Sharing those instances across archives was silently
 * corrupting data when two archives were processed at the same time.
 *
 * <h2>Dispatch model</h2>
 * MPQ has two mutually incompatible interpretations of a sector's leading
 * compression-type byte, and StormLib picks between them by format version
 * ({@code SCompDecompressX}):
 * <ul>
 * <li><b>Format version 0 and 1</b> use {@code SCompDecompressInternal}: the
 * byte is a <em>bit mask</em>. Every set bit must correspond to a known
 * algorithm, and the algorithms are applied in {@link CompressionType}
 * declaration order. LZMA is not reachable here, so {@code 0x12} legitimately
 * means {@code BZIP2 | ZLIB}.</li>
 * <li><b>Format version 2 and above</b> use {@code SCompDecompress2}: the byte
 * is matched <em>exactly</em> against a small fixed set of values, one of which
 * is {@code 0x12} for standalone LZMA.</li>
 * </ul>
 * The previous implementation tested {@code (type & 0x12) != 0} for LZMA, which
 * fires for plain deflate ({@code 0x02}) and plain BZIP2 ({@code 0x10}) and so
 * rejected sectors it could actually read.
 */
public final class CompressionUtil {
    /**
     * Size of the MPQ-specific LZMA blob header: one filter byte, five LZMA
     * property bytes, and an eight byte uncompressed size. Matches StormLib's
     * {@code LZMA_HEADER_SIZE}.
     */
    private static final int LZMA_HEADER_SIZE = 1 + 5 + 8;

    private CompressionUtil() {
    }

    /**
     * Compresses one sector's worth of data.
     *
     * @param data       raw sector content.
     * @param recompress compression strategy.
     * @return the compressed bytes <em>without</em> the leading
     *         compression-type byte, which the caller prepends, or {@code null}
     *         when no recompression was asked for and the caller should store
     *         the data as it is.
     */
    public static byte[] compress(byte[] data, RecompressOptions recompress) {
        if (!recompress.recompress) {
            // Nothing, and the caller stores the sector raw. This used to build
            // a zlib stream of stored blocks, which is by construction larger
            // than its input -- so every sector paid for a full copy and an
            // Adler-32 to produce something the caller always discarded, because
            // it applies a "did it actually shrink" test. The archives written
            // are byte for byte identical without it.
            return null;
        }
        if (recompress.useZopfli) {
            return zopfli(data, recompress.iterations);
        }
        return JzLibHelper.deflate(data, true);
    }

    /**
     * Zopfli deflate, which is an optional dependency.
     * <p>
     * It is not a published dependency of this library: it is reachable only
     * through {@link RecompressOptions#useZopfli}, and it is distributed through
     * JitPack rather than Maven Central, so depending on it normally would force
     * every consumer to add that repository for a codec most will never call.
     * Asking for it without it on the classpath is a configuration mistake, and
     * saying so beats a bare {@link NoClassDefFoundError}.
     *
     * @param data       data to compress.
     * @param iterations zopfli iteration count.
     * @return a zlib stream.
     */
    private static byte[] zopfli(byte[] data, int iterations) {
        try {
            return new ZopfliHelper().deflate(data, iterations);
        } catch (NoClassDefFoundError missing) {
            throw new IllegalStateException("RecompressOptions.useZopfli needs the optional"
                + " Zopfli dependency on the classpath. Add"
                + " com.github.eustas:CafeUndZopfli (from https://jitpack.io) or leave"
                + " useZopfli off to compress with the JDK deflater.", missing);
        }
    }

    /**
     * Decompresses a sector of a format version 0 or 1 archive.
     *
     * @param sector           sector bytes, starting with the compression-type
     *                         byte.
     * @param compressedSize   number of valid bytes in {@code sector}.
     * @param uncompressedSize expected size of the decompressed sector.
     * @return the decompressed sector.
     * @throws JMpqException if the compression-type byte names an algorithm this
     *                       library cannot apply, or the data is corrupt.
     */
    public static byte[] decompress(byte[] sector, int compressedSize, int uncompressedSize) throws JMpqException {
        return decompress(sector, compressedSize, uncompressedSize, 0);
    }

    /**
     * Decompresses a sector.
     *
     * @param sector           sector bytes, starting with the compression-type
     *                         byte.
     * @param compressedSize   number of valid bytes in {@code sector}.
     * @param uncompressedSize expected size of the decompressed sector.
     * @param formatVersion    MPQ format version of the containing archive;
     *                         selects the dispatch model, see class docs.
     * @return the decompressed sector.
     * @throws JMpqException if the compression-type byte names an algorithm this
     *                       library cannot apply, or the data is corrupt.
     */
    public static byte[] decompress(byte[] sector, int compressedSize, int uncompressedSize, int formatVersion)
        throws JMpqException {
        // A sector whose stored size equals its natural size was never
        // compressed and carries no type byte at all.
        if (compressedSize == uncompressedSize) {
            return sector;
        }
        if (compressedSize < 1) {
            throw new JMpqException("Compressed sector is empty.");
        }

        final int type = sector[0] & 0xFF;
        final byte[] result = formatVersion >= 2
            ? decompressExact(type, sector, compressedSize, uncompressedSize)
            : decompressMasked(type, sector, compressedSize, uncompressedSize);

        if (result.length == uncompressedSize) {
            return result;
        }
        // Sector sizes are exact in MPQ; a short result means the sector is
        // damaged. Report it rather than silently handing back zero padding.
        throw new JMpqException("Sector decompressed to " + result.length
            + " bytes, expected " + uncompressedSize + " (compression type 0x"
            + Integer.toHexString(type) + ").");
    }

    /**
     * Format version 0/1 dispatch: mask driven, StormLib
     * {@code SCompDecompressInternal}.
     */
    private static byte[] decompressMasked(int type, byte[] sector, int compressedSize, int uncompressedSize)
        throws JMpqException {
        final List<CompressionType> stages = new ArrayList<>(2);
        int unclaimed = type;
        for (CompressionType candidate : CompressionType.values()) {
            if ((type & candidate.mask()) != 0) {
                stages.add(candidate);
                unclaimed &= ~candidate.mask();
            }
        }
        if (stages.isEmpty() || unclaimed != 0) {
            throw new JMpqException("Unsupported sector compression mask 0x" + Integer.toHexString(type) + ".");
        }
        return applyAll(stages, sector, 1, compressedSize - 1, uncompressedSize);
    }

    /**
     * Format version 2+ dispatch: exact match, StormLib
     * {@code SCompDecompress2}.
     */
    private static byte[] decompressExact(int type, byte[] sector, int compressedSize, int uncompressedSize)
        throws JMpqException {
        final int payloadOffset = 1;
        final int payloadLength = compressedSize - 1;

        // Pattern matching over the fixed set StormLib recognises. Anything
        // else is a corrupt sector, not an unsupported feature.
        final List<CompressionType> stages = switch (type) {
            case 0x02 -> List.of(CompressionType.ZLIB);
            case 0x08 -> List.of(CompressionType.PKWARE);
            case 0x10 -> List.of(CompressionType.BZIP2);
            case 0x20 -> List.of(CompressionType.SPARSE);
            case 0x22 -> List.of(CompressionType.ZLIB, CompressionType.SPARSE);
            case 0x30 -> List.of(CompressionType.BZIP2, CompressionType.SPARSE);
            case 0x41 -> List.of(CompressionType.HUFFMAN, CompressionType.ADPCM_MONO);
            case 0x81 -> List.of(CompressionType.HUFFMAN, CompressionType.ADPCM_STEREO);
            case CompressionType.MASK_LZMA -> null; // handled below
            default -> throw new JMpqException(
                "Unsupported sector compression type 0x" + Integer.toHexString(type) + " for format version 2+.");
        };

        if (stages == null) {
            return lzma(sector, payloadOffset, payloadLength, uncompressedSize);
        }
        return applyAll(stages, sector, payloadOffset, payloadLength, uncompressedSize);
    }

    private static byte[] applyAll(List<CompressionType> stages, byte[] sector, int offset, int length,
                                   int uncompressedSize) throws JMpqException {
        byte[] current = null;
        int currentOffset = offset;
        int currentLength = length;

        for (CompressionType stage : stages) {
            final byte[] produced = applyGuarded(stage, current == null ? sector : current,
                currentOffset, currentLength, uncompressedSize);
            current = produced;
            currentOffset = 0;
            currentLength = produced.length;
        }
        // stages is never empty, so current is never null here.
        return current;
    }

    /**
     * Runs one decompression stage, translating any codec failure into a
     * {@link JMpqException}.
     * <p>
     * The codecs are ports of C code and signal bad input with whatever came to
     * hand: {@code IllegalStateException} for a zlib error code,
     * {@code IllegalArgumentException} for a malformed PKWARE header,
     * {@code BufferUnderflowException} from the Huffman and ADPCM readers.
     * Those are all data conditions, not programming errors, and letting them
     * escape unchecked meant one corrupt file aborted an entire
     * {@code extractAllFiles} sweep. Translating here covers every codec at
     * once, including any added later.
     */
    private static byte[] applyGuarded(CompressionType stage, byte[] in, int offset, int length,
                                       int uncompressedSize) throws JMpqException {
        try {
            return apply(stage, in, offset, length, uncompressedSize);
        } catch (JMpqException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new JMpqException("Corrupt " + stage + " data: " + e, e);
        }
    }

    private static byte[] apply(CompressionType stage, byte[] in, int offset, int length, int uncompressedSize)
        throws JMpqException {
        return switch (stage) {
            case ZLIB -> JzLibHelper.inflate(in, offset, length, uncompressedSize);
            case PKWARE -> pkware(in, offset, length, uncompressedSize);
            case BZIP2 -> bzip2(in, offset, length, uncompressedSize);
            case SPARSE -> Sparse.decompress(in, offset, length, uncompressedSize);
            case HUFFMAN -> huffman(in, offset, length, uncompressedSize);
            case ADPCM_MONO -> adpcm(in, offset, length, uncompressedSize, 1);
            case ADPCM_STEREO -> adpcm(in, offset, length, uncompressedSize, 2);
        };
    }

    private static byte[] pkware(byte[] in, int offset, int length, int uncompressedSize) {
        final byte[] out = new byte[uncompressedSize];
        // Exploder reads from an absolute index, so the slice bounds are
        // expressed through the offset rather than by copying.
        Exploder.pkexplode(sliceIfNeeded(in, offset, length), out, 0);
        return out;
    }

    private static byte[] bzip2(byte[] in, int offset, int length, int uncompressedSize) throws JMpqException {
        try (InputStream stream = new BZip2CompressorInputStream(new ByteArrayInputStream(in, offset, length))) {
            return readExactly(stream, uncompressedSize);
        } catch (IOException e) {
            throw new JMpqException("Cannot decompress BZIP2 sector.", e);
        }
    }

    private static byte[] lzma(byte[] in, int offset, int length, int uncompressedSize) throws JMpqException {
        if (length <= LZMA_HEADER_SIZE) {
            throw new JMpqException("LZMA sector too short: " + length + " bytes.");
        }
        if (in[offset] != 0) {
            throw new JMpqException("LZMA sector uses filter " + in[offset] + ", only 0 is supported.");
        }
        // Skipping the filter byte leaves exactly the 13 byte "lzma alone"
        // header (5 property bytes plus an 8 byte size) followed by the raw
        // stream, which is what LZMAInputStream expects.
        try (InputStream stream = new LZMAInputStream(
            new ByteArrayInputStream(in, offset + 1, length - 1))) {
            return readExactly(stream, uncompressedSize);
        } catch (IOException e) {
            throw new JMpqException("Cannot decompress LZMA sector.", e);
        }
    }

    private static byte[] huffman(byte[] in, int offset, int length, int uncompressedSize) {
        final ByteBuffer source = ByteBuffer.wrap(in, offset, length).slice();
        final ByteBuffer target = ByteBuffer.allocate(uncompressedSize);
        new Huffman().decompress(source, target);
        return trimmed(target);
    }

    private static byte[] adpcm(byte[] in, int offset, int length, int uncompressedSize, int channels) {
        final ByteBuffer source = ByteBuffer.wrap(in, offset, length).slice();
        final ByteBuffer target = ByteBuffer.allocate(uncompressedSize);
        new ADPCM(channels).decompress(source, target, channels);
        return trimmed(target);
    }

    private static byte[] trimmed(ByteBuffer target) {
        final byte[] out = new byte[target.position()];
        target.flip();
        target.get(out);
        return out;
    }

    private static byte[] sliceIfNeeded(byte[] in, int offset, int length) {
        if (offset == 0 && length == in.length) {
            return in;
        }
        final byte[] slice = new byte[length];
        System.arraycopy(in, offset, slice, 0, length);
        return slice;
    }

    private static byte[] readExactly(InputStream stream, int expected) throws IOException {
        final byte[] out = new byte[expected];
        int read = 0;
        while (read < expected) {
            final int n = stream.read(out, read, expected - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read == expected ? out : java.util.Arrays.copyOf(out, read);
    }

    /**
     * Decompresses a sector of a file carrying the whole-file
     * {@code MPQ_FILE_IMPLODE} flag.
     * <p>
     * Such sectors are always PKWARE imploded and carry <em>no</em> leading
     * compression-type byte, so they bypass the dispatch entirely.
     *
     * @param sector           sector bytes.
     * @param compressedSize   number of valid bytes in {@code sector}.
     * @param uncompressedSize expected size of the decompressed sector.
     * @return the decompressed sector.
     */
    public static byte[] explode(byte[] sector, int compressedSize, int uncompressedSize) throws JMpqException {
        if (compressedSize == uncompressedSize) {
            return sector;
        }
        final byte[] out = new byte[uncompressedSize];
        try {
            Exploder.pkexplode(sector, out, 0);
        } catch (RuntimeException e) {
            // Same reasoning as applyGuarded: the exploder reports malformed
            // input with unchecked exceptions.
            throw new JMpqException("Corrupt imploded data: " + e, e);
        }
        return out;
    }

    /** Kept for source compatibility with the pre-2.0 flag constant. */
    public static final byte FLAG_DEFLATE = 0x02;

    /**
     * Set of algorithms this build can decompress. Exposed for diagnostics and
     * for the format-support matrix in the README.
     */
    public static EnumSet<CompressionType> supportedDecompressions() {
        return EnumSet.allOf(CompressionType.class);
    }
}
