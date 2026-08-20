package org.inwc3.jmpq;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

/**
 * The archive image being built: a little-endian, append-mostly byte sink that
 * grows on demand and supports back-patching earlier regions.
 *
 * <h2>Why not map the output</h2>
 * The pre-2.0 writer mapped regions of a temporary file, sized by guesses such
 * as {@code fileData.length * 2}. Guessing too low overflowed the mapping,
 * mapped buffers were never deterministically unmapped so the file stayed
 * locked on Windows, and the staging directory was shared between every archive
 * in the JVM. Building in memory needs none of that: after compression the
 * exact sizes are known, nothing touches the filesystem until the finished
 * image is handed to a sink, and there is no global state to race on.
 *
 * <h2>High-water mark</h2>
 * {@link #size()} tracks the furthest byte ever written, not the current
 * position. The writer reserves a header at the front, appends the archive, then
 * seeks back to fill the header in; a position-based length would truncate the
 * archive at that point.
 *
 * <h2>Reserve and advance</h2>
 * {@link #reserve(int)} makes a worst-case region addressable without counting
 * it, and {@link #advance(int)} then counts only what was produced. An encoder
 * needs room to work in but usually fills less of it, and counting the unused
 * tail would leave stale bytes in the finished archive.
 */
final class MpqImageBuffer {
    private static final int MIN_CAPACITY = 64;

    /** Largest image this buffer will grow to, bounded by array addressing. */
    static final int MAX_SIZE = Integer.MAX_VALUE - 8;

    private byte[] data;
    private int position;
    private int highWaterMark;

    MpqImageBuffer(int initialCapacity) {
        this.data = new byte[Math.clamp(initialCapacity, MIN_CAPACITY, MAX_SIZE)];
    }

    /**
     * @return the length of the image built so far.
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
     *                    reserve space, which reads as zeroes.
     */
    void position(int newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Position cannot be negative: " + newPosition);
        }
        ensureCapacity(newPosition);
        this.position = newPosition;
    }

    /**
     * Reserves zero bytes at the current position and counts them.
     *
     * @param count number of bytes to reserve.
     */
    void skip(int count) {
        require(count >= 0, "Cannot skip a negative number of bytes: " + count);
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
     * @param index absolute offset to write at.
     * @param src   bytes to write.
     */
    void putAt(int index, byte[] src) {
        require(index >= 0, "Index cannot be negative: " + index);
        ensureCapacity(index + src.length);
        System.arraycopy(src, 0, data, index, src.length);
        highWaterMark = Math.max(highWaterMark, index + src.length);
    }

    /**
     * Makes a region addressable so an encoder can write into the image with no
     * intermediate copy, without counting it as written.
     * <p>
     * The view is invalidated by anything that grows this buffer, so it must be
     * finished with before the next write. Follow it with
     * {@link #advance(int)}.
     *
     * @param length number of bytes to make addressable.
     * @return a little-endian view over that region.
     */
    ByteBuffer reserve(int length) {
        require(length >= 0, "Cannot reserve a negative number of bytes: " + length);
        ensureCapacity(position + length);
        return ByteBuffer.wrap(data, position, length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Moves past bytes written through a {@link #reserve(int)} view.
     *
     * @param length number of bytes actually produced.
     */
    void advance(int length) {
        require(length >= 0, "Cannot advance by a negative number of bytes: " + length);
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
     * @param target stream to write the image to; not closed.
     * @throws IOException if the stream rejects the write.
     */
    void writeTo(OutputStream target) throws IOException {
        target.write(data, 0, highWaterMark);
        target.flush();
    }

    /**
     * @param target channel to write the image to at its current position.
     * @throws IOException if the channel rejects the write.
     */
    void writeTo(WritableByteChannel target) throws IOException {
        final ByteBuffer view = ByteBuffer.wrap(data, 0, highWaterMark);
        while (view.hasRemaining()) {
            if (target.write(view) < 1) {
                throw new IOException("Cannot write archive image: the channel accepted no bytes.");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureCapacity(int required) {
        if (required < 0 || required > MAX_SIZE) {
            throw new OutOfMemoryError("Archive image would exceed " + MAX_SIZE + " bytes.");
        }
        if (required <= data.length) {
            return;
        }
        int capacity = data.length;
        while (capacity < required) {
            final int doubled = capacity << 1;
            // Saturate rather than overflow into a negative capacity.
            capacity = doubled > 0 && doubled <= MAX_SIZE ? doubled : MAX_SIZE;
        }
        data = Arrays.copyOf(data, capacity);
    }
}
