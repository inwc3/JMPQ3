package org.inwc3.jmpq;

import systems.crigges.jmpq3.JMpqException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Random-access, bounds-checked, little-endian view over the bytes of an MPQ
 * archive.
 * <p>
 * Every read the library performs goes through one of these, whether the
 * archive came from a file, a byte array or a channel, so the parsing code has
 * a single access model.
 *
 * <h2>Why a MemorySegment</h2>
 * A file-backed source maps the file into an {@link Arena}-scoped
 * {@link MemorySegment}. That buys three things the previous
 * {@code MappedByteBuffer} approach could not:
 * <ul>
 * <li><b>Deterministic unmap.</b> Closing the arena releases the mapping at
 * once. Mapped byte buffers are unmapped only when the garbage collector gets
 * round to them, which on Windows left the archive locked long after
 * {@code close()} returned — the reason the old rebuild had to stage through a
 * temporary file.</li>
 * <li><b>No 2 GiB ceiling.</b> Segment offsets are {@code long}, so a v2+
 * archive larger than {@link Integer#MAX_VALUE} is addressable without
 * chunking.</li>
 * <li><b>Bounds checking by construction.</b> Every access is checked against
 * the segment, so a malformed offset cannot read adjacent memory. Out-of-range
 * reads are reported as {@link JMpqException} rather than
 * {@link IndexOutOfBoundsException}, because they mean a damaged archive rather
 * than a bug.</li>
 * </ul>
 *
 * <h2>Exception type</h2>
 * Data errors are reported as {@link JMpqException}, which lives in the legacy
 * {@code jmpq3} package on purpose. It is the exception every existing consumer
 * already catches, and giving the new core a parallel type would either break
 * those catch blocks or force the compatibility adapter to translate between
 * two identical exceptions. One type, no wrapping.
 *
 * <h2>Thread safety</h2>
 * A source created with {@link #ofFile(Path)} is confined to the thread that
 * created it, because its arena is. {@link #ofArray(byte[])} sources are
 * unconfined and may be read concurrently. Neither is mutable.
 */
public final class MpqSource implements AutoCloseable {
    private static final ValueLayout.OfShort LE_SHORT =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment segment;

    /** The arena backing a mapped file, or {@code null} for a heap source. */
    private final Arena arena;

    /** Describes where these bytes came from, for diagnostics. */
    private final String origin;

    private MpqSource(MemorySegment segment, Arena arena, String origin) {
        this.segment = segment;
        this.arena = arena;
        this.origin = origin;
    }

    /**
     * Maps an archive file for reading.
     *
     * @param path archive to map; must exist.
     * @return a source over the file's bytes.
     * @throws IOException if the file cannot be opened or mapped.
     */
    public static MpqSource ofFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new JMpqException("Not an MPQ archive file: " + path.toAbsolutePath());
        }
        final Arena arena = Arena.ofConfined();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            final MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            return new MpqSource(mapped, arena, path.toAbsolutePath().toString());
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Wraps an archive already held in memory.
     * <p>
     * The array is <em>not</em> copied, and this source never writes, so the
     * caller may keep using it. Writing is a separate operation that produces
     * new bytes rather than mutating these.
     *
     * @param archive archive bytes.
     * @return a source over those bytes.
     */
    public static MpqSource ofArray(byte[] archive) {
        return new MpqSource(MemorySegment.ofArray(archive), null, "byte[" + archive.length + "]");
    }

    /**
     * Reads a channel fully into memory and wraps the result.
     * <p>
     * A channel cannot be mapped in general, so its contents are copied. Prefer
     * {@link #ofFile(Path)} for files, which maps instead of copying.
     *
     * @param channel channel positioned anywhere; read in full from 0.
     * @return a source over the channel's contents.
     * @throws IOException if the channel cannot be read.
     */
    public static MpqSource ofChannel(SeekableByteChannel channel) throws IOException {
        final long size = channel.size();
        if (size > Integer.MAX_VALUE - 8) {
            throw new JMpqException("Channel holds " + size
                + " bytes, too large to read into memory; open it as a file instead.");
        }
        final byte[] bytes = new byte[(int) size];
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        channel.position(0);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new JMpqException("Channel ended after " + buffer.position()
                    + " of " + size + " bytes.");
            }
        }
        return new MpqSource(MemorySegment.ofArray(bytes), null, "channel[" + size + "]");
    }

    /**
     * @return the number of bytes available.
     */
    public long size() {
        return segment.byteSize();
    }

    /**
     * @return where these bytes came from, for use in diagnostics.
     */
    public String origin() {
        return origin;
    }

    /**
     * @param offset byte offset.
     * @return the unsigned byte at {@code offset}, as 0..255.
     * @throws JMpqException if the offset is outside the archive.
     */
    public int u8(long offset) throws JMpqException {
        checkRange(offset, 1);
        return Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, offset));
    }

    /**
     * @param offset byte offset.
     * @return the unsigned little-endian 16-bit value at {@code offset}.
     * @throws JMpqException if the range is outside the archive.
     */
    public int u16(long offset) throws JMpqException {
        checkRange(offset, 2);
        return Short.toUnsignedInt(segment.get(LE_SHORT, offset));
    }

    /**
     * @param offset byte offset.
     * @return the signed little-endian 32-bit value at {@code offset}.
     * @throws JMpqException if the range is outside the archive.
     */
    public int i32(long offset) throws JMpqException {
        checkRange(offset, 4);
        return segment.get(LE_INT, offset);
    }

    /**
     * @param offset byte offset.
     * @return the unsigned little-endian 32-bit value at {@code offset}.
     * @throws JMpqException if the range is outside the archive.
     */
    public long u32(long offset) throws JMpqException {
        return Integer.toUnsignedLong(i32(offset));
    }

    /**
     * @param offset byte offset.
     * @return the signed little-endian 64-bit value at {@code offset}.
     * @throws JMpqException if the range is outside the archive.
     */
    public long i64(long offset) throws JMpqException {
        checkRange(offset, 8);
        return segment.get(LE_LONG, offset);
    }

    /**
     * Copies a range out into a new array.
     *
     * @param offset byte offset of the first byte.
     * @param length number of bytes.
     * @return a new array holding that range.
     * @throws JMpqException if the range is outside the archive, or too large
     *                       for an array.
     */
    public byte[] bytes(long offset, int length) throws JMpqException {
        if (length < 0) {
            throw new JMpqException("Cannot read a negative number of bytes: " + length);
        }
        checkRange(offset, length);
        return segment.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * @param offset byte offset.
     * @param length number of bytes.
     * @return whether that range lies inside the archive.
     */
    public boolean contains(long offset, long length) {
        return offset >= 0 && length >= 0 && offset <= size() - length;
    }

    private void checkRange(long offset, long length) throws JMpqException {
        if (!contains(offset, length)) {
            throw new JMpqException("Read of " + length + " bytes at " + offset
                + " lies outside the " + size() + " byte archive (" + origin + ").");
        }
    }

    /**
     * Releases the mapping, if this source has one.
     * <p>
     * For a file-backed source the file is fully released by the time this
     * returns, so it can be deleted or replaced immediately.
     */
    @Override
    public void close() {
        if (arena != null) {
            arena.close();
        }
    }
}
