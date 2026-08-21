package systems.crigges.jmpq3test;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The seeded-zero Adler-32 that MPQ sector checksums use.
 * <p>
 * Two things need pinning. That the value matches what StormLib computes, which
 * is what the literal constants below are for; and that the fast path -- the
 * JDK intrinsic plus a seed correction -- agrees with the definition computed
 * directly, which is what the fuzz comparison is for. Deriving one from the
 * other is an algebraic shortcut, and a shortcut nobody checks is a bug waiting
 * to happen.
 */
public class MpqChecksumTests {

    private static Method fast;
    private static Method reference;

    private static void load() throws Exception {
        if (fast == null) {
            final Class<?> type = Class.forName("org.inwc3.jmpq.MpqChecksums");
            fast = type.getDeclaredMethod("adler32", byte[].class, int.class, int.class);
            reference = type.getDeclaredMethod("adler32Reference", byte[].class, int.class, int.class);
            fast.setAccessible(true);
            reference.setAccessible(true);
        }
    }

    private static int fast(byte[] data, int offset, int length) throws Exception {
        load();
        return (int) fast.invoke(null, data, offset, length);
    }

    private static int reference(byte[] data, int offset, int length) throws Exception {
        load();
        return (int) reference.invoke(null, data, offset, length);
    }

    /** Values taken from {@code zlib.adler32(data, 0)}, not from this code. */
    @Test
    public void knownValuesMatchZlibSeededWithZero() throws Exception {
        final byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        Assert.assertEquals(fast(abc, 0, abc.length), 0x024A0126,
            "seeding with 1 would give 0x024D0127");
        Assert.assertEquals(fast(new byte[0], 0, 0), 0);

        final byte[] many = new byte[10_000];
        java.util.Arrays.fill(many, (byte) 'a');
        Assert.assertEquals(fast(many, 0, many.length), 0x78ABCDE2,
            "long enough to cross the block boundary the accumulator folds at");
    }

    /**
     * The intrinsic-plus-correction path against the definition, over lengths
     * that straddle every boundary that matters: empty, one byte, and either
     * side of zlib's 5552-byte fold.
     */
    @Test
    public void theFastPathAgreesWithTheDefinition() throws Exception {
        final Random random = new Random(11);
        final int[] lengths = {0, 1, 2, 15, 16, 255, 4096, 5551, 5552, 5553, 11_104, 40_000};

        for (int length : lengths) {
            final byte[] data = new byte[length];
            random.nextBytes(data);
            Assert.assertEquals(fast(data, 0, length), reference(data, 0, length),
                "length " + length);

            // All-zero and all-0xFF exercise the modulus at both extremes.
            Assert.assertEquals(fast(new byte[length], 0, length),
                reference(new byte[length], 0, length), "zeroes, length " + length);
            final byte[] high = new byte[length];
            java.util.Arrays.fill(high, (byte) 0xFF);
            Assert.assertEquals(fast(high, 0, length), reference(high, 0, length),
                "0xFF, length " + length);
        }
    }

    /** Offsets and lengths inside a larger array must be honoured. */
    @Test
    public void slicesAreHonoured() throws Exception {
        final Random random = new Random(12);
        final byte[] data = new byte[8192];
        random.nextBytes(data);

        for (int offset : new int[]{0, 1, 7, 4095}) {
            for (int length : new int[]{0, 1, 100, 4000}) {
                Assert.assertEquals(fast(data, offset, length), reference(data, offset, length),
                    "offset " + offset + " length " + length);
            }
        }
    }
}
