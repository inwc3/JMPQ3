package systems.crigges.jmpq3.compression;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;

import java.util.Arrays;

/**
 * Faster jzlib helper tuned for level 0 (no compression).
 * NOTE: Not thread-safe.
 */
public class JzLibHelper {
    // If your consumer accepts RAW DEFLATE (no zlib header/Adler32),
    // set this to true for level 0 to shave a bit more overhead.
    private static final boolean RAW_NOWRAP_FOR_LEVEL0 = false;

    private static final Inflater INF = new Inflater();
    private static Deflater DEF;
    private static int currentLevel = Integer.MIN_VALUE;
    private static boolean currentNowrap = false;

    private static byte[] comp = new byte[1024];

    public static byte[] inflate(byte[] bytes, int offset, int uncompSize) {
        byte[] out = new byte[uncompSize];

        INF.init(); // default = zlib wrapper
        // Use correct remaining length (original used bytes.length - 1)
        INF.setInput(bytes, offset, bytes.length - offset, true);

        int outPos = 0;
        while (outPos < uncompSize) {
            // Provide remaining space in one go
            INF.setOutput(out, outPos, uncompSize - outPos);
            int rc = INF.inflate(JZlib.Z_NO_FLUSH);

            if (rc == JZlib.Z_STREAM_END) {
                outPos = (int) INF.getTotalOut();
                break;
            }
            if (rc == JZlib.Z_OK || rc == JZlib.Z_BUF_ERROR) {
                // Update outPos from total_out (cumulative)
                outPos = (int) INF.getTotalOut();

                // If no input left AND we didn't hit STREAM_END, break to avoid spin
                if (INF.avail_in == 0 && rc == JZlib.Z_BUF_ERROR) break;
                continue;
            }
            INF.end();
            throw new RuntimeException("inflate error: " + rc);
        }

        INF.end();
        return out;
    }

    public static byte[] deflate(byte[] bytes, boolean strongDeflate) {
        final int level = strongDeflate ? JZlib.Z_BEST_COMPRESSION : JZlib.Z_NO_COMPRESSION;
        final boolean nowrap = (!strongDeflate) && RAW_NOWRAP_FOR_LEVEL0; // raw only for level 0

        ensureDeflater(level, nowrap);
        ensureCompCapacity(bytes.length, !nowrap);

        // Attach full input/output once; jzlib manages avail_* internally
        DEF.setInput(bytes, 0, bytes.length, true);
        DEF.setOutput(comp, 0, comp.length);

        // Main compress loop
        while (true) {
            int rc = DEF.deflate(JZlib.Z_NO_FLUSH);
            if (rc == JZlib.Z_OK || rc == JZlib.Z_BUF_ERROR) {
                // If all input consumed, move to finish
                if (DEF.avail_in == 0) break;

                // If out buffer is full, grow and reattach at current position
                if (DEF.avail_out == 0) {
                    growComp();
                    DEF.setOutput(comp, (int) DEF.getTotalOut(), comp.length - (int) DEF.getTotalOut());
                }
                continue;
            }
            throw new RuntimeException("deflate(Z_NO_FLUSH) error: " + rc);
        }

        // Finish
        while (true) {
            if (DEF.avail_out == 0) {
                growComp();
                DEF.setOutput(comp, (int) DEF.getTotalOut(), comp.length - (int) DEF.getTotalOut());
            }
            int rc = DEF.deflate(JZlib.Z_FINISH);
            if (rc == JZlib.Z_STREAM_END) break;
            if (rc != JZlib.Z_OK && rc != JZlib.Z_BUF_ERROR) {
                throw new RuntimeException("deflate(Z_FINISH) error: " + rc);
            }
        }

        int outLen = (int) DEF.getTotalOut();
        byte[] out = Arrays.copyOf(comp, outLen);

        // Ready for next call: re-init with desired params then
        // set again in ensureDeflater() on next invocation.
        return out;
    }

    // -------------------- INTERNALS --------------------

    private static void ensureDeflater(int level, boolean nowrap) {
        try {
            if (DEF == null) {
                DEF = new Deflater(level, nowrap);
                currentLevel = level;
                currentNowrap = nowrap;
            } else {
                if (currentLevel != level || currentNowrap != nowrap) {
                    // Re-init with new level/nowrap (supported by jzlib)
                    DEF.init(level, nowrap);
                    currentLevel = level;
                    currentNowrap = nowrap;
                } else {
                    // Same settings; start a fresh stream
                    DEF.init(level, nowrap);
                }
            }

        } catch (GZIPException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ensureCompCapacity(int inputLen, boolean zlibWrapper) {
        int worst = worstCaseZlibSize(inputLen, zlibWrapper);
        if (comp.length < worst) {
            comp = new byte[worst];
        } else {
            // Attach the full buffer from start for this stream
            DEF.setOutput(comp, 0, comp.length);
        }
    }

    /**
     * Worst-case for stored blocks + optional zlib header/adler.
     * Each stored block (<=65535 bytes) = 5 bytes overhead.
     */
    private static int worstCaseZlibSize(int n, boolean zlibWrapper) {
        int blocks = (n + 65534) / 65535;       // number of 5-byte stored block headers
        int storedOverhead = blocks * 5;
        int header = zlibWrapper ? 2 /*zlib header*/ + 4 /*Adler32*/ : 0;
        return n + storedOverhead + header + 16; // a little slack
    }

    private static void growComp() {
        comp = Arrays.copyOf(comp, comp.length * 2);
    }
}
