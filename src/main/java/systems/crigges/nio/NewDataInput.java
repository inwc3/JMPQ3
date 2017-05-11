
package systems.crigges.nio;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ScatteringByteChannel;

/**
 * New DataInput style interface for providing access to binary data in a
 * convenient and platform independent way.
 * <p>
 * Many primitive type read methods are implemented as defaults for convince.
 * <p>
 * String methods are not supported. Reading String objects should be done by
 * reading some quantity of bytes and then encoding into an appropriate String.
 * <p>
 * Due to the low level nature of these methods no synchronisation is performed.
 * Behaviour is undefined if multiple threads try to read data at the same time
 */
public interface NewDataInput extends DataInput, DataByteOrder, ScatteringByteChannel {
    /**
     * Convenience method to read in an unsigned int in the form of a long.
     * 
     * @return the unsigned 32-bit value read.
     */
    default long readUnsignedInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    default boolean readBoolean() throws IOException {
        return readByte() != 0 ? true : false;
    }

    @Override
    default int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(readByte());
    }

    @Override
    default int readUnsignedShort() throws IOException {
        return Short.toUnsignedInt(readShort());
    }

    @Override
    default char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    default String readLine() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    default String readUTF() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    default long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        long done = 0;
        for (int i = offset; i < length; i += 1) {
            done += read(dsts[i]);
        }
        return done;
    }

    @Override
    default long read(ByteBuffer[] dsts) throws IOException {
        return read(dsts, 0, dsts.length);
    }

}
