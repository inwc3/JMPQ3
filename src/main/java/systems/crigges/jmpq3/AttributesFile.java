package systems.crigges.jmpq3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.CRC32;

public class AttributesFile {
    private Logger log = LoggerFactory.getLogger(this.getClass().getName());
    private byte[] file;

    private int[] crc32;
    private long[] timestamps;
    private HashMap<String, Integer> refMap = new HashMap<>();

    private CRC32 crcGen = new CRC32();

    public AttributesFile(int entries) {
        this.file = new byte[8 + 12 * entries];
        this.file[0] = 100; // Format Version
        this.file[4] = 3; // Attributes bytemask (crc,timestamp,[md5])
        crc32 = new int[entries];
        timestamps = new long[entries];
    }

    public AttributesFile(byte[] file) {
        this.file = file;
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(8);
        int fileCount = (file.length - 8) / 12 - 1;
        crc32 = new int[fileCount];
        timestamps = new long[fileCount];
        for (int i = 0; i < fileCount; i++) {
            crc32[i] = buffer.getInt();
        }
        for (int i = 0; i < fileCount; i++) {
            timestamps[i] = buffer.getLong();
        }
        log.debug("parsed attributes");
    }

    public void setEntry(int i, int crc, long timestamp) {
        crc32[i] = crc;
        timestamps[i] = timestamp;
    }

    public byte[] buildFile() {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(8);
        for(int crc : crc32) {
            buffer.putInt(crc);
        }
        for(long timestamp : timestamps) {
            buffer.putLong(timestamp);
        }
        return buffer.array();
    }

    public int entries() {
        return crc32.length;
    }

    public int[] getCrc32() {
        return crc32;
    }

    public long[] getTimestamps() {
        return timestamps;
    }

    public byte[] getFile() {
        return file;
    }

    public void setNames(ArrayList<String> names) {
        int i = 0;
        for(String name : names) {
            refMap.put(name, i);
            i++;
        }
    }

    public int getEntry(String name) {
            return refMap.containsKey(name) ? refMap.get(name) : -1;
    }

    private int getCrc32(File file) throws IOException {
        return getCrc32(Files.readAllBytes(file.toPath()));
    }

    public int getCrc32(byte[] bytes) throws JMpqException {
        crcGen.reset();
        crcGen.update(bytes);
        return (int) crcGen.getValue();
    }
}