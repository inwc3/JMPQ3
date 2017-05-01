package systems.crigges.jmpq3;

import java.nio.ByteBuffer;

public class AttributesFile {
    private byte[] file;
    private int[] crc32;

    private long[] timestamps;

    public AttributesFile(byte[] file) {
        this.file = file;
        ByteBuffer buffer = ByteBuffer.wrap(file);
        buffer.position(8);
        int fileCount = (file.length - 8) / 12 - 1;
        crc32 = new int[fileCount];
        timestamps = new long[fileCount];
        for (int i = 0; i < fileCount; i++) {
            crc32[i] = buffer.getInt();
            timestamps[i] = buffer.getLong();
        }
        System.out.println("parsed attributes");
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
}