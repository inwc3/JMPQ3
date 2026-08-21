package systems.crigges.jmpq3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class DebugHelper {
    protected static final char[] hexArray = "0123456789ABCDEF".toCharArray();

    /** How many bytes a dump shows before giving up. */
    private static final int MAX_BYTES = 500;

    /**
     * @param bytes bytes to render.
     * @return space-separated hex, truncated to the first {@value #MAX_BYTES}
     *         bytes. This is a diagnostic aid, not a serialisation format.
     */
    public static String bytesToHex(byte[] bytes) {
        // Sized to what is actually rendered. Allocating for the full array and
        // relying on trim() to remove the unwritten tail meant a multi-megabyte
        // buffer to print half a kilobyte.
        final int shown = Math.min(bytes.length, MAX_BYTES);
        final char[] hexChars = new char[shown * 3];
        for (int j = 0; j < shown; j++) {
            final int v = bytes[j] & 0xFF;
            hexChars[j * 3] = hexArray[v >>> 4];
            hexChars[j * 3 + 1] = hexArray[v & 0xF];
            hexChars[j * 3 + 2] = ' ';
        }
        return new String(hexChars).trim();
    }

    public static byte[] appendData(byte firstObject,byte[] secondObject){
        byte[] byteArray= {firstObject};
        return appendData(byteArray,secondObject);
    }

    public static byte[] appendData(byte[] firstObject,byte[] secondObject){
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream( );
        try {
            if (firstObject!=null && firstObject.length!=0)
                outputStream.write(firstObject);
            if (secondObject!=null && secondObject.length!=0)
                outputStream.write(secondObject);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot append byte arrays.", e);
        }
        return outputStream.toByteArray();
    }
}