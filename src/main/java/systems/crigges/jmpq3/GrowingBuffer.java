package systems.crigges.jmpq3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

/**
 * A little-endian, append-mostly byte sink that grows on demand and supports
 * back-patching earlier regions.
 * <p>
 * This is what replaced the archive rebuild's two previous strategies, both of
 * which were sources of real bugs:
 * <ul>
 * <li>Writing through {@link java.nio.MappedByteBuffer} regions sized by
 * guesses such as {@code fileData.length * 2}. Guessing too low overflowed the
 * mapping; mapped buffers are also never unmapped deterministically, which kept
 * the archive locked on Windows long after {@code close()}.</li>
 * <li>Staging into a shared temp directory under {@code java.io.tmpdir}, which
 * every open wiped, racing any other archive open in the same or another
 * process.</li>
 * </ul>
 * A growing heap buffer needs neither: after compression the exact sizes are
 * known, nothing touches the filesystem until the finished image is written
 * out, and there is no global state to race on.
 *
 * <h2>High-water mark</h2>
 * {@link #size()} tracks the furthest byte ever written, not the current
 * position. The rebuild reserves a header at the front, appends the whole
 * archive, then seeks back to fill the header in; a plain position-based length
 * would truncate the archive at that point. (w3p's {@code DynamicByteBuffer} had
 * exactly that flaw: growing while the position was rewound discarded
 * everything past it.)
 *
 * <h2>Thread safety</h2>
 * Not thread safe; one instance belongs to one rebuild.
 */
final class GrowingBuffer {
    private static final int MIN_CAPACITY = 64;

    private byte[] data;

    /** Next write position. */
    private int position;

    /** One past the furthest byte ever written. */
    private int highWaterMark;

    /**
     * @param initialCapacity starting capacity; clamped to a sane minimum.
     */
    GrowingBuffer(int initialCapacity) {
        this.data = new byte[Math.max(MIN_CAPACITY, initialCapacity)];
    }

    /**
     * @return the number of bytes written, i.e. the length of the image built
     *         so far.
     */
    int size() {
        return highWaterMark;
    }

    /**
     * @return the current write position.
     */
    int position() {
        return position;
    }

    /**
     * @param newPosition new write position; may exceed {@link #size()} to
     *                    reserve space, which is then zero filled.
     */
    void position(int newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Position cannot be negative: " + newPosition);
        }
        ensureCapacity(newPosition);
        this.position = newPosition;
    }

    /**
     * Reserves {@code count} zero bytes at the current position and skips past
     * them.
     *
     * @param count number of bytes to reserve.
     */
    void skip(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Cannot skip a negative number of bytes: " + count);
        }
        ensureCapacity(position + count);
        position += count;
        highWaterMark = Math.max(highWaterMark, position);
    }

    void put(byte[] src) {
        put(src, 0, src.length);
    }

    void put(byte[] src, int offset, int length) {
        ensureCapacity(position + length);
        System.arraycopy(src, offset, data, position, length);
        position += length;
        highWaterMark = Math.max(highWaterMark, position);
    }

    void put(ByteBuffer src) {
        final int length = src.remaining();
        ensureCapacity(position + length);
        src.get(data, position, length);
        position += length;
        highWaterMark = Math.max(highWaterMark, position);
    }

    void putInt(int value) {
        ensureCapacity(position + 4);
        data[position] = (byte) value;
        data[position + 1] = (byte) (value >>> 8);
        data[position + 2] = (byte) (value >>> 16);
        data[position + 3] = (byte) (value >>> 24);
        position += 4;
        highWaterMark = Math.max(highWaterMark, position);
    }

    /**
     * Overwrites an already written region without moving the position.
     *
     * @param index  absolute offset to write at; must lie inside
     *               {@link #size()} plus {@code src.length}.
     * @param src    bytes to write.
     */
    void putAt(int index, byte[] src) {
        if (index < 0) {
            throw new IllegalArgumentException("Index cannot be negative: " + index);
        }
        ensureCapacity(index + src.length);
        System.arraycopy(src, 0, data, index, src.length);
        highWaterMark = Math.max(highWaterMark, index + src.length);
    }

    /**
     * Hands out a {@link ByteBuffer} view of {@code length} bytes at the current
     * position, so an encoder can write straight into the image with no
     * intermediate copy.
     * <p>
     * Neither the position nor the length of the image moves: the region is
     * only made addressable. Call {@link #advance(int)} afterwards with the
     * number of bytes actually produced. That split matters because an encoder
     * needs a worst-case region to work in but usually fills less of it, and
     * counting the unused tail would leave garbage in the finished archive.
     * <p>
     * The view is invalidated by anything that grows this buffer, so it must be
     * finished with first.
     *
     * @param length number of bytes to make addressable.
     * @return a little-endian view over that region.
     */
    ByteBuffer reserve(int length) {
        ensureCapacity(position + length);
        return ByteBuffer.wrap(data, position, length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Moves past bytes written through a {@link #reserve(int)} view.
     *
     * @param length number of bytes actually produced.
     */
    void advance(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Cannot advance by a negative number of bytes: " + length);
        }
        ensureCapacity(position + length);
        position += length;
        highWaterMark = Math.max(highWaterMark, position);
    }

    /**
     * @return a copy of the bytes written so far.
     */
    byte[] toByteArray() {
        return Arrays.copyOf(data, highWaterMark);
    }

    /**
     * Writes the image to a channel.
     *
     * @param dest channel to write to, at its current position.
     * @throws IOException if the channel rejects the write.
     */
    void writeTo(WritableByteChannel dest) throws IOException {
        final ByteBuffer view = ByteBuffer.wrap(data, 0, highWaterMark);
        while (view.hasRemaining()) {
            if (dest.write(view) < 1) {
                throw new IOException("Cannot write archive image: channel accepted no bytes.");
            }
        }
    }

    private void ensureCapacity(int required) {
        if (required < 0) {
            throw new OutOfMemoryError("Archive image exceeds 2 GiB.");
        }
        if (required <= data.length) {
            return;
        }
        int capacity = data.length;
        while (capacity < required) {
            final int doubled = capacity << 1;
            // Saturate rather than overflow into a negative capacity.
            capacity = doubled > 0 ? doubled : Integer.MAX_VALUE - 8;
        }
        data = Arrays.copyOf(data, capacity);
    }
}
