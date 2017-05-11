package systems.crigges.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SeekableByteChannel;

/**
 * A wrapper for SeekableByteChannel objects that adds buffering functionality
 * allowing for efficient RandomAccessFile like IO of primitive data types.
 * <p>
 * This class is useful when an unknown or variable amount of data has to be
 * read in such that ByteBuffers might not be efficient or convenient to use
 * directly. It also makes the guarantee that reads and writes will process all
 * the requested data or fail with an exception.
 * <p>
 * For consistency and efficiency a single ByteBuffer is used for both reading
 * and writing data. The buffer is flushed when switching between read and write
 * modes meaning that regularly interleaving such methods could result in
 * reduced buffer utilisation and poor performance.
 * <p>
 * The wrapped channel position does not reflect this's position. When in read
 * mode it will be placed at the end of the buffer. When in write mode it will
 * be placed at the beginning of the buffer. This allows efficient sequential
 * operation.
 * <p>
 * Behaviour is undefined if used to wrap a non-blocking channel. Behaviour is
 * undefined if the position of the wrapped channel is manipulated by anything
 * other than this wrapper. Closing this channel will also close the wrapped
 * channel.
 */
public class BufferedByteChannel implements SeekableByteChannel, DataInput, DataOutput {
    /**
     * The channel that is being wrapped.
     */
    private final SeekableByteChannel channel;

    /**
     * The buffer to store read/written data.
     */
    private ByteBuffer buffer;

    /**
     * If the currently buffered data has to be written out to the wrapped
     * channel.
     */
    private boolean writeOut = false;

    /**
     * Construct by wrapping the provided channel. Buffer size influences how
     * efficiently data is read.
     * <p>
     * The buffer must be at least large enough to allow for reading and writing
     * of primitive types, 8 bytes.
     * 
     * @param channel
     *            channel to wrap.
     * @param bufferSize
     *            size of read/write buffer in bytes.
     */
    public BufferedByteChannel(final SeekableByteChannel channel, final int bufferSize) {
        if (bufferSize < 8)
            throw new IllegalArgumentException("bufferSize is too small.");

        this.channel = channel;
        buffer = ByteBuffer.allocate(bufferSize);
        buffer.limit(0);
    }

    /**
     * Construct by wrapping the provided channel. Uses a 4KB buffer.
     * 
     * @param channel
     *            channel to wrap.
     */
    public BufferedByteChannel(final SeekableByteChannel channel) {
        this(channel, 4096);
    }

    /**
     * Internal convenience method to write out the currently buffered data.
     * After writing is finished the buffer will be reset for use.
     * <p>
     * Behaviour is undefined if called when buffer is not in writing mode.
     */
    private void writeOutBuffer() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw new IllegalBlockingModeException();
            }
        }
        buffer.clear();
    }

    /**
     * Flush the underlying ByteBuffer.
     * <p>
     * If data has only been read then the currently buffered data is discarded.
     * If data has been written then the currently buffered data is written out
     * and then discarded. In either case the wrapped channel will end up at the
     * absolute position.
     * 
     * @throws IOException
     *             if an IOException occurs while writing out buffered data.
     */
    public void flushBuffer() throws IOException {
        if (writeOut) {
            writeOutBuffer();
        } else {
            channel.position(this.position());
            buffer.limit(0);
        }
    }

    /**
     * Fill the buffer with data from the wrapped channel. Not yet processed
     * data is moved to the start of the buffer.
     * 
     * @throws IOException
     *             if an IOException occurs while reading in data.
     */
    private void fillBuffer() throws IOException {
        buffer.compact();
        while (buffer.hasRemaining()) {
            switch (channel.read(buffer)) {
            case 0:
                throw new IllegalBlockingModeException();
            case -1:
                buffer.limit(buffer.position());
            }
        }
        buffer.flip();
    }

    /**
     * Prepare the data buffer for writing. Enforces that the buffer be able to
     * accept at least a minimum number of bytes, writing out buffered data as
     * required.
     * 
     * @param minlen
     *            the minimum number of bytes the buffer must accept.
     * @throws IOException
     *             if an IOException occurs while writing out buffered data.
     */
    private void prepareWrite(final int minlen) throws IOException {
        // switch to write mode if required
        if (!writeOut) {
            channel.position(this.position());
            buffer.clear();
            writeOut = true;
        }

        // enforce minimum capacity
        if (buffer.remaining() < minlen) {
            writeOutBuffer();
        }
    }

    /**
     * Prepare the data buffer for reading. Enforces that the buffer contains at
     * least a minimum number of bytes, reading in data or writing out buffered
     * data as required.
     * 
     * @param minlen
     *            the minimum number of bytes the buffer must contain.
     * @throws IOException
     *             if an IOException occurs while reading in data or writing out
     *             buffered data.
     */
    private void prepareRead(final int minlen) throws IOException {
        // switch to read mode if required
        if (writeOut) {
            writeOutBuffer();
            buffer.limit(0);
            fillBuffer();
            writeOut = false;
        }

        // enforce minimum capacity
        if (buffer.remaining() < minlen) {
            fillBuffer();
        }
    }

    /**
     * Sets the byte order used to interpret primitive types from the buffer.
     * 
     * @param bo
     *            the byte order to use.
     */
    public void order(final ByteOrder bo) {
        buffer.order(bo);
    }

    /**
     * Convenience method to read in an unsigned int in the form of a long.
     * 
     * @return the unsigned 32-bit value read.
     */
    public long readUnsignedInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public void close() throws IOException {
        flushBuffer();
        channel.close();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public long position() throws IOException {
        if (writeOut) {
            return channel.position() + buffer.position();
        }
        return channel.position() - buffer.remaining();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        // resolve buffer range
        long start = channel.position();
        long end = start;
        if (writeOut) {
            end += buffer.position();
        } else {
            start -= buffer.limit();
        }

        // keep current buffer if new position is in range
        if (start <= newPosition && newPosition <= end) {
            buffer.position((int) (newPosition - start));
        } else {
            flushBuffer();
            channel.position(newPosition);
        }

        return this;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        prepareRead(0);

        final int read = dst.remaining();
        if (read < buffer.remaining()) {
            // read from buffer
            final int limit = buffer.limit();
            buffer.limit(buffer.position() + read);
            dst.put(buffer);
            buffer.limit(limit);
        } else {
            // empty buffer and read from channel
            dst.put(buffer);
            buffer.limit(0);
            while (dst.hasRemaining()) {
                switch (channel.read(dst)) {
                case 0:
                    throw new IllegalBlockingModeException();
                case -1:
                    throw new EOFException();
                }
            }
        }

        return read;
    }

    @Override
    public long size() throws IOException {
        long size = channel.size();

        // adjust for file appending buffers
        if (writeOut && position() > size) {
            size = Math.max(size, position());
        }

        return size;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        flushBuffer();
        channel.truncate(size);
        return this;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        prepareWrite(0);

        final int write = src.remaining();
        if (write <= buffer.remaining()) {
            // write to buffer
            buffer.put(src);
        } else {
            // write out buffer and write to channel
            writeOutBuffer();
            while (src.hasRemaining()) {
                if (channel.write(src) <= 0) {
                    throw new IllegalBlockingModeException();
                }
            }
        }

        return write;
    }

    @Override
    public void write(int b) throws IOException {
        prepareWrite(1);

        buffer.put((byte) b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(ByteBuffer.wrap(b));
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        write(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        write(v);
    }

    @Override
    public void writeShort(int v) throws IOException {
        prepareWrite(2);

        buffer.putShort((short) v);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        prepareWrite(4);

        buffer.putInt(v);
    }

    @Override
    public void writeLong(long v) throws IOException {
        prepareWrite(8);

        buffer.putLong(v);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        prepareWrite(4);

        buffer.putFloat(v);
    }

    @Override
    public void writeDouble(double v) throws IOException {
        prepareWrite(8);

        buffer.putDouble(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeChars(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeUTF(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        read(ByteBuffer.wrap(b));
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        read(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public int skipBytes(int n) throws IOException {
        final long currentPos = position();
        final long nextPos = Math.min(currentPos + n, size());
        position(nextPos);

        return (int) (nextPos - currentPos);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0 ? true : false;
    }

    @Override
    public byte readByte() throws IOException {
        prepareRead(1);

        return buffer.get();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(readByte());
    }

    @Override
    public short readShort() throws IOException {
        prepareRead(2);

        return buffer.getShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return Short.toUnsignedInt(readShort());
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public int readInt() throws IOException {
        prepareRead(4);

        return buffer.getInt();
    }

    @Override
    public long readLong() throws IOException {
        prepareRead(8);

        return buffer.getLong();
    }

    @Override
    public float readFloat() throws IOException {
        prepareRead(4);

        return buffer.getFloat();
    }

    @Override
    public double readDouble() throws IOException {
        prepareRead(8);

        return buffer.getDouble();
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readUTF() throws IOException {
        throw new UnsupportedOperationException();
    }
}
