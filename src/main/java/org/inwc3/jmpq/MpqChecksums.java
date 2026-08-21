package org.inwc3.jmpq;

/**
 * The Adler-32 variant MPQ sector checksums use.
 *
 * <h2>Why this is not {@link java.util.zip.Adler32}</h2>
 * StormLib computes sector checksums as {@code adler32(0, buffer, length)} —
 * both when writing them ({@code SFileAddFile.cpp}) and when checking them
 * ({@code ReadMpqSectors} in {@code SFileReadFile.cpp}). Passing zlib a seed of
 * {@code 0} starts the accumulators at {@code s1 = 0, s2 = 0}, whereas a
 * standard Adler-32 — and so {@code java.util.zip.Adler32}, which offers no way
 * to seed it — starts at {@code s1 = 1}. The results differ by 1 in the low half
 * and by the byte count in the high half, for every input.
 * <p>
 * That is a difference no self-consistent test can see: a reader and a writer
 * that both use the standard seed agree with each other perfectly and disagree
 * with every archive StormLib ever wrote. It was caught by
 * {@code tools/mpqref.py}, which computes the value independently, and is the
 * reason that cross-check exists.
 */
final class MpqChecksums {

    /** Largest Adler-32 modulus below 65536. */
    private static final int BASE = 65521;

    /**
     * Largest number of bytes that can be accumulated before {@code s2} could
     * overflow a signed 32-bit int. zlib calls this {@code NMAX}.
     */
    private static final int NMAX = 5552;

    private MpqChecksums() {
    }

    /**
     * @param data bytes to checksum.
     * @return the sector checksum MPQ records: zlib's {@code adler32} seeded
     *         with 0 rather than the standard 1.
     */
    static int adler32(byte[] data) {
        return adler32(data, 0, data.length);
    }

    /**
     * @param data   bytes to checksum.
     * @param offset first byte to include.
     * @param length how many bytes to include.
     * @return the sector checksum MPQ records.
     */
    static int adler32(byte[] data, int offset, int length) {
        int s1 = 0;
        int s2 = 0;
        int at = offset;
        int remaining = length;

        while (remaining > 0) {
            final int block = Math.min(remaining, NMAX);
            for (int i = 0; i < block; i++) {
                s1 += data[at++] & 0xFF;
                s2 += s1;
            }
            s1 %= BASE;
            s2 %= BASE;
            remaining -= block;
        }
        return (s2 << 16) | s1;
    }
}
