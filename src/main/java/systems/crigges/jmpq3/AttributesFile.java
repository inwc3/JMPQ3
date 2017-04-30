package systems.crigges.jmpq3;

import java.nio.ByteBuffer;

public class AttributesFile {
    private int[] crc32;
    private long[] timestamps;

    public AttributesFile(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.position(8);
        int fileCount = (file.length - 8) / 12;
        crc32 = new int[fileCount];
        timestamps = new long[fileCount];
        for (int i = 0; i < fileCount; i++) {
            crc32[i] = buffer.getInt();
            timestamps[i] = buffer.getLong();
        }
    }

}