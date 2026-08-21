package systems.crigges.jmpq3.compression;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate and inflate for MPQ sectors.
 *
 * <h2>Why not jzlib any more (P3-2)</h2>
 * This used to wrap {@code com.jcraft:jzlib}, a pure-Java zlib port last
 * released in 2011. {@link Deflater} and {@link Inflater} do the same job
 * through the JDK's bundled zlib, which is native and maintained, so the
 * dependency bought nothing but a supply-chain risk and a slower codec on the
 * hottest path in the library.
 * <p>
 * The class name and signatures are unchanged because they are public API that
 * tests and downstream code call.
 *
 * <h2>Thread safety</h2>
 * Every call creates and ends its own {@link Inflater}/{@link Deflater}. An
 * older version held them in static fields with a shared scratch array and
 * documented itself as "not thread-safe"; two archives compressed at the same
 * time silently produced corrupt sectors.
 */
public final class JzLibHelper {

    private JzLibHelper() {
    }

    /**
     * @param bytes      buffer holding the deflate stream.
     * @param offset     index of the first stream byte.
     * @param uncompSize expected size of the inflated output.
     * @return the inflated bytes.
     */
    public static byte[] inflate(byte[] bytes, int offset, int uncompSize) {
        return inflate(bytes, offset, bytes.length - offset, uncompSize);
    }

    /**
     * Inflates a zlib stream.
     * <p>
     * A stream that ends early yields what it produced rather than an error:
     * the caller compares the length against what the block table promised and
     * reports the shortfall with the file's name attached, which is a better
     * diagnostic than one from in here. Genuinely malformed data is a different
     * matter and is thrown.
     *
     * @param bytes      buffer holding the deflate stream.
     * @param offset     index of the first stream byte.
     * @param length     number of stream bytes available.
     * @param uncompSize expected size of the inflated output.
     * @return the inflated bytes, truncated to what the stream actually
     *         produced.
     */
    public static byte[] inflate(byte[] bytes, int offset, int length, int uncompSize) {
        if (uncompSize == 0) {
            return new byte[0];
        }
        final byte[] out = new byte[uncompSize];
        final Inflater inflater = new Inflater();

        try {
            inflater.setInput(bytes, offset, length);

            int outPos = 0;
            while (outPos < uncompSize && !inflater.finished()) {
                final int produced = inflater.inflate(out, outPos, uncompSize - outPos);
                if (produced == 0) {
                    // No progress and nothing left to feed it: the stream stops
                    // short of what the block table claimed.
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                outPos += produced;
            }
            return outPos == uncompSize ? out : Arrays.copyOf(out, outPos);
        } catch (DataFormatException e) {
            throw new IllegalStateException("inflate error: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /**
     * @param bytes         data to compress.
     * @param strongDeflate {@code true} for maximum compression. {@code false}
     *                      is no longer a meaningful request — see
     *                      {@link CompressionUtil#compress} — and is treated as
     *                      maximum compression rather than silently producing
     *                      something larger than the input.
     * @return the deflate stream.
     */
    public static byte[] deflate(byte[] bytes, boolean strongDeflate) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(bytes);
            deflater.finish();

            // Worst case for incompressible input: stored blocks, each covering
            // at most 65535 bytes at a cost of 5 bytes, plus the zlib wrapper.
            final int blocks = (int) (((long) bytes.length + 65534) / 65535);
            byte[] out = new byte[bytes.length + blocks * 5 + 6 + 16];

            int written = 0;
            while (!deflater.finished()) {
                if (written == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                written += deflater.deflate(out, written, out.length - written);
            }
            return written == out.length ? out : Arrays.copyOf(out, written);
        } finally {
            deflater.end();
        }
    }
}
