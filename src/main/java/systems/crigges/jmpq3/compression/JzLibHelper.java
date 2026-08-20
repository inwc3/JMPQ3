package systems.crigges.jmpq3.compression;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;

import java.util.Arrays;

/**
 * Deflate/inflate via jzlib.
 * <p>
 * <b>Thread safety:</b> every call creates and ends its own
 * {@link Inflater}/{@link Deflater}. The previous implementation held them in
 * static fields together with a shared scratch array and documented itself as
 * "not thread-safe"; two archives compressed at the same time silently produced
 * corrupt sectors.
 */
public final class JzLibHelper {
    /**
     * Whether level-0 output may omit the zlib header and Adler-32 trailer.
     * MPQ consumers expect the wrapper, so this stays off.
     */
    private static final boolean RAW_NOWRAP_FOR_LEVEL0 = false;

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
     * @param bytes      buffer holding the deflate stream.
     * @param offset     index of the first stream byte.
     * @param length     number of stream bytes available.
     * @param uncompSize expected size of the inflated output.
     * @return the inflated bytes, truncated to what the stream actually
     *         produced.
     */
    public static byte[] inflate(byte[] bytes, int offset, int length, int uncompSize) {
        final byte[] out = new byte[uncompSize];
        final Inflater inf = new Inflater();

        try {
            inf.init(); // default = zlib wrapper
            inf.setInput(bytes, offset, length, true);

            int outPos = 0;
            while (outPos < uncompSize) {
                inf.setOutput(out, outPos, uncompSize - outPos);
                final int rc = inf.inflate(JZlib.Z_NO_FLUSH);

                if (rc == JZlib.Z_STREAM_END) {
                    outPos = (int) inf.getTotalOut();
                    break;
                }
                if (rc == JZlib.Z_OK || rc == JZlib.Z_BUF_ERROR) {
                    outPos = (int) inf.getTotalOut();

                    // No input left and no progress possible: stop instead of
                    // spinning on a truncated stream.
                    if (inf.avail_in == 0 && rc == JZlib.Z_BUF_ERROR) {
                        break;
                    }
                    continue;
                }
                throw new IllegalStateException("inflate error: " + rc);
            }

            return outPos == uncompSize ? out : Arrays.copyOf(out, outPos);
        } finally {
            inf.end();
        }
    }

    /**
     * @param bytes          data to compress.
     * @param strongDeflate  {@code true} for maximum compression, {@code false}
     *                       for stored blocks only.
     * @return the deflate stream.
     */
    public static byte[] deflate(byte[] bytes, boolean strongDeflate) {
        final int level = strongDeflate ? JZlib.Z_BEST_COMPRESSION : JZlib.Z_NO_COMPRESSION;
        final boolean nowrap = !strongDeflate && RAW_NOWRAP_FOR_LEVEL0;
        final Deflater def = newDeflater(level, nowrap);
        byte[] comp = new byte[worstCaseZlibSize(bytes.length, !nowrap)];

        try {
            def.setInput(bytes, 0, bytes.length, true);
            def.setOutput(comp, 0, comp.length);

            while (true) {
                final int rc = def.deflate(JZlib.Z_NO_FLUSH);
                if (rc == JZlib.Z_OK || rc == JZlib.Z_BUF_ERROR) {
                    if (def.avail_in == 0) {
                        break;
                    }
                    if (def.avail_out == 0) {
                        comp = grow(comp);
                        def.setOutput(comp, (int) def.getTotalOut(), comp.length - (int) def.getTotalOut());
                    }
                    continue;
                }
                throw new IllegalStateException("deflate(Z_NO_FLUSH) error: " + rc);
            }

            while (true) {
                if (def.avail_out == 0) {
                    comp = grow(comp);
                    def.setOutput(comp, (int) def.getTotalOut(), comp.length - (int) def.getTotalOut());
                }
                final int rc = def.deflate(JZlib.Z_FINISH);
                if (rc == JZlib.Z_STREAM_END) {
                    break;
                }
                if (rc != JZlib.Z_OK && rc != JZlib.Z_BUF_ERROR) {
                    throw new IllegalStateException("deflate(Z_FINISH) error: " + rc);
                }
            }

            return Arrays.copyOf(comp, (int) def.getTotalOut());
        } finally {
            def.end();
        }
    }

    private static Deflater newDeflater(int level, boolean nowrap) {
        try {
            return new Deflater(level, nowrap);
        } catch (GZIPException e) {
            throw new IllegalStateException("Cannot create deflater.", e);
        }
    }

    /**
     * Worst case for stored blocks plus the optional zlib wrapper. Each stored
     * block covers at most 65535 bytes and costs 5 bytes of header.
     */
    private static int worstCaseZlibSize(int n, boolean zlibWrapper) {
        final int blocks = (int) (((long) n + 65534) / 65535);
        final int header = zlibWrapper ? 2 + 4 : 0;
        return n + blocks * 5 + header + 16;
    }

    private static byte[] grow(byte[] comp) {
        return Arrays.copyOf(comp, Math.max(64, comp.length * 2));
    }
}
