package systems.crigges.jmpq3;

import org.inwc3.jmpq.MpqAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.CRC32;

/**
 * The {@code (attributes)} file.
 *
 * @deprecated use {@link org.inwc3.jmpq.MpqAttributes}, which models the whole
 *     format rather than the one shape this class assumed.
 */
@Deprecated(since = "2.0", forRemoval = false)
public class AttributesFile {
    private static final Logger log = LoggerFactory.getLogger(AttributesFile.class);

    private final byte[] file;

    private final int[] crc32;
    private final long[] timestamps;
    private final HashMap<String, Integer> refMap = new HashMap<>();

    private final CRC32 crcGen = new CRC32();

    /**
     * @param entries how many blocks to describe.
     */
    public AttributesFile(int entries) {
        this.file = new byte[8 + 12 * entries];
        this.file[0] = 100; // Format Version
        this.file[4] = 3; // Attributes bytemask (crc,timestamp,[md5])
        crc32 = new int[entries];
        timestamps = new long[entries];
    }

    /**
     * Parses an attributes file.
     * <p>
     * P2-4: the entry count is now derived from the bytemask the file declares,
     * rather than from an assumed CRC32-plus-timestamp layout with an
     * unexplained entry subtracted. The old {@code (length - 8) / 12 - 1} was
     * wrong three ways: it ignored the bytemask it had just read, it misread any
     * file carrying MD5 digests, and the {@code - 1} hardcoded one of the
     * several lengths StormLib tolerates instead of working out which one this
     * file is.
     * <p>
     * Without the archive's block count this can only infer the count from the
     * length, so it takes the largest count that fits. {@link MpqAttributes}
     * does it properly, given the block count it needs.
     *
     * @param file the file content.
     */
    public AttributesFile(byte[] file) {
        this.file = file;
        final ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(4);
        final int flags = file.length >= 8 ? buffer.getInt() : 0;
        final int usable = flags & MpqAttributes.KNOWN_FLAGS;

        // A bytemask naming no array this implementation knows describes no
        // entries. Without the guard the loop below never terminates, because
        // every count then has the same size as every other.
        int entries = 0;
        if (usable != 0) {
            while (MpqAttributes.sizeFor(usable, entries + 1) <= file.length) {
                entries++;
            }
        }

        crc32 = (usable & MpqAttributes.HAS_CRC32) != 0 ? new int[entries] : new int[0];
        for (int i = 0; i < crc32.length; i++) {
            crc32[i] = buffer.getInt();
        }
        timestamps = (usable & MpqAttributes.HAS_FILETIME) != 0 ? new long[entries] : new long[0];
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = buffer.getLong();
        }
        log.debug("parsed attributes: flags 0x{}, {} entries",
            Integer.toHexString(flags), entries);
    }

    /**
     * @param i         block index.
     * @param crc       the block's CRC32.
     * @param timestamp the block's Windows FILETIME.
     */
    public void setEntry(int i, int crc, long timestamp) {
        crc32[i] = crc;
        timestamps[i] = timestamp;
    }

    /**
     * @return the serialised file.
     */
    public byte[] buildFile() {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(8);
        for (int crc : crc32) {
            buffer.putInt(crc);
        }
        for (long timestamp : timestamps) {
            buffer.putLong(timestamp);
        }
        return buffer.array();
    }

    /**
     * @return how many blocks are described.
     */
    public int entries() {
        return Math.max(crc32.length, timestamps.length);
    }

    /**
     * @return the CRC32 array, which is empty when the file declared none.
     */
    public int[] getCrc32() {
        return crc32;
    }

    /**
     * @return the timestamp array, which is empty when the file declared none.
     */
    public long[] getTimestamps() {
        return timestamps;
    }

    /**
     * @return the raw file bytes.
     */
    public byte[] getFile() {
        return file;
    }

    /**
     * @param names names in block order.
     */
    public void setNames(ArrayList<String> names) {
        int i = 0;
        for (String name : names) {
            refMap.put(name, i);
            i++;
        }
    }

    /**
     * @param name a file name.
     * @return its index, or -1.
     */
    public int getEntry(String name) {
        return refMap.getOrDefault(name, -1);
    }

    /**
     * @param bytes a file's decoded content.
     * @return its zlib CRC32.
     */
    public int getCrc32(byte[] bytes) {
        crcGen.reset();
        crcGen.update(bytes);
        return (int) crcGen.getValue();
    }
}
