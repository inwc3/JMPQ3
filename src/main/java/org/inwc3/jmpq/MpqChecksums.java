package org.inwc3.jmpq;

import java.util.zip.Adler32;

/**
 * The Adler-32 variant MPQ sector checksums use.
 *
 * <h2>Why this is not plain {@link Adler32}</h2>
 * StormLib computes sector checksums as {@code adler32(0, buffer, length)} —
 * both when writing them ({@code SFileAddFile.cpp}) and when checking them
 * ({@code ReadMpqSectors} in {@code SFileReadFile.cpp}). Passing zlib a seed of
 * {@code 0} starts the accumulators at {@code s1 = 0, s2 = 0}, whereas a
 * standard Adler-32 — and so {@link Adler32}, which offers no way to seed it —
 * starts at {@code s1 = 1}. The results differ by 1 in the low half and by the
 * byte count in the high half, for every input.
 * <p>
 * That is a difference no self-consistent test can see: a reader and a writer
 * that both use the standard seed agree with each other perfectly and disagree
 * with every archive StormLib ever wrote. It was caught by
 * {@code tools/mpqref.py}, which computes the value independently, and is the
 * reason that cross-check exists.
 *
 * <h2>Getting it from the intrinsic anyway</h2>
 * The two seeds differ by a closed form rather than by anything structural, so
 * the JDK's implementation can still do the work. Running the recurrence from
 * {@code s1 = 1} instead of {@code s1 = 0} adds exactly 1 to the low half, and
 * adds 1 per byte to the high half:
 * <pre>
 * s1(seed 1) = s1(seed 0) + 1
 * s2(seed 1) = s2(seed 0) + n
 * </pre>
 * So the seeded-zero value is recovered by subtracting those, modulo 65521.
 * {@link Adler32#update(byte[], int, int)} is a HotSpot intrinsic, which a
 * hand-written loop in Java is not — worth having when a single sector can be
 * 16 MiB and every sector of every file passes through here.
 */
final class MpqChecksums {

    /** Largest Adler-32 modulus below 65536. */
    private static final int BASE = 65521;

    /**
     * Largest number of bytes the reference implementation accumulates before
     * reducing. zlib calls this {@code NMAX}.
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
        final Adler32 standard = new Adler32();
        standard.update(data, offset, length);
        final int seededOne = (int) standard.getValue();

        // Undo the seed: 1 from the low half, one per byte from the high half.
        final int low = Math.floorMod((seededOne & 0xFFFF) - 1, BASE);
        final int high = Math.floorMod(((seededOne >>> 16) & 0xFFFF) - length % BASE, BASE);
        return (high << 16) | low;
    }

    /**
     * The definition, computed directly.
     * <p>
     * Kept as the oracle {@code MpqChecksumTests} checks {@link #adler32}
     * against, so the seed correction above cannot drift from what it claims to
     * compute. Not used in production: it is the same arithmetic without the
     * intrinsic.
     *
     * @param data   bytes to checksum.
     * @param offset first byte to include.
     * @param length how many bytes to include.
     * @return the sector checksum MPQ records.
     */
    static int adler32Reference(byte[] data, int offset, int length) {
        // long accumulators, deliberately. zlib picks NMAX so that s2 cannot
        // overflow an *unsigned* 32-bit accumulator; Java has no such type, and
        // a signed int overflows at half that. An earlier version of this method
        // was the production path and used int, so it silently produced wrong
        // checksums for a sector of a few thousand high-valued bytes -- reachable
        // as soon as an archive uses a sector size above the 4 KiB default.
        long s1 = 0;
        long s2 = 0;
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
        return (int) ((s2 << 16) | s1);
    }
}
