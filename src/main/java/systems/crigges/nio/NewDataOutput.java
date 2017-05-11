/**
 * 
 */
package systems.crigges.nio;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;

/**
 * New DataOutput style interface for allowing creation of binary data in a
 * convenient and platform independent way.
 * <p>
 * Many primitive type write methods are implemented as defaults for convince.
 * <p>
 * String methods are not supported. Writing String objects should be done by
 * decoding the string into some quantity of bytes and then writing those bytes
 * out.
 * <p>
 * Due to the low level nature of these methods no synchronisation is performed.
 * Behaviour is undefined if multiple threads try to write data at the same time
 */
public interface NewDataOutput extends DataOutput, DataByteOrder, GatheringByteChannel {
    @Override
    default void write(int b) throws IOException {
        writeByte(b);
    }

    @Override
    default void writeBoolean(boolean v) throws IOException {
        writeByte(v ? 1 : 0);
    }

    @Override
    default void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    default void writeBytes(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    default void writeChars(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    default void writeUTF(String s) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    default long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        long done = 0;
        for (int i = offset; i < length; i += 1) {
            done += write(srcs[i]);
        }
        return done;
    }

    @Override
    default long write(ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }
}
