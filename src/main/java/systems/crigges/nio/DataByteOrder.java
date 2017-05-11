package systems.crigges.nio;

import java.nio.ByteOrder;

/**
 * Interface for specifying the ByteOrder used to interpret primitive data.
 */
public interface DataByteOrder {
    /**
     * Sets the byte order used to interpret mutli-byte primitives from a
     * channel.
     * 
     * @param bo
     *            the byte order to use.
     */
    public void order(ByteOrder bo);
}
