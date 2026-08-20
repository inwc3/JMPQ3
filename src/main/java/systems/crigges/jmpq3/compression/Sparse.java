package systems.crigges.jmpq3.compression;

import systems.crigges.jmpq3.JMpqException;

/**
 * Run-length ("sparse") compression, mask {@code 0x20}.
 * <p>
 * Ported from StormLib {@code src/sparse/sparse.cpp}. The stream begins with a
 * big-endian 32-bit decompressed length, followed by control bytes: a control
 * byte with the high bit set introduces {@code (b & 0x7F) + 1} literal bytes, a
 * control byte without it stands for {@code (b & 0x7F) + 3} zero bytes.
 */
public final class Sparse {
    private Sparse() {
    }

    /**
     * @param in                  compressed input.
     * @param offset              first byte of the sparse stream in
     *                            {@code in}.
     * @param length              number of sparse stream bytes.
     * @param maxDecompressedSize capacity of the caller's output buffer.
     * @return the decompressed bytes, exactly as long as the length recorded in
     *         the stream header.
     * @throws JMpqException if the stream is truncated, malformed, or claims to
     *                       be larger than {@code maxDecompressedSize}.
     */
    public static byte[] decompress(byte[] in, int offset, int length, int maxDecompressedSize) throws JMpqException {
        // StormLib refuses anything shorter than the 4 byte size plus one
        // control byte.
        if (length < 5) {
            throw new JMpqException("Sparse stream too short: " + length + " bytes.");
        }

        int pos = offset;
        final int end = offset + length;

        final long declaredSize = ((long) (in[pos] & 0xFF) << 24)
            | ((long) (in[pos + 1] & 0xFF) << 16)
            | ((long) (in[pos + 2] & 0xFF) << 8)
            | (in[pos + 3] & 0xFF);
        pos += 4;

        if (declaredSize > maxDecompressedSize) {
            throw new JMpqException("Sparse stream declares " + declaredSize
                + " bytes but at most " + maxDecompressedSize + " were expected.");
        }

        final byte[] out = new byte[(int) declaredSize];
        int outPos = 0;

        while (pos < end) {
            final int control = in[pos++] & 0xFF;
            final int remaining = out.length - outPos;

            if ((control & 0x80) != 0) {
                // Literal run.
                int chunk = (control & 0x7F) + 1;
                if (pos + chunk > end) {
                    throw new JMpqException("Truncated sparse literal run.");
                }
                final int copied = Math.min(chunk, remaining);
                System.arraycopy(in, pos, out, outPos, copied);
                pos += chunk;
                outPos += copied;
            } else {
                // Zero run: the output array is already zero filled, so only
                // the cursor has to advance.
                final int chunk = (control & 0x7F) + 3;
                outPos += Math.min(chunk, remaining);
            }
        }

        if (outPos != out.length) {
            // The control runs stopped short of the declared length. Returning
            // the buffer anyway would hand back the missing tail as zeros, and
            // because its length still matches what the caller expected, the
            // corruption would be accepted silently.
            throw new JMpqException("Sparse stream declared " + out.length
                + " bytes but its control runs produced " + outPos + ".");
        }
        return out;
    }
}
