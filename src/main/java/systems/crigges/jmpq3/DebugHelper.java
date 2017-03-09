package systems.crigges.jmpq3;

public class DebugHelper {
    protected static final char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 3];
        for (int j = 0; j < Math.min(bytes.length, 500); j++) {
            int v = bytes[j] & 0xFF;
            hexChars[(j * 3)] = hexArray[(v >>> 4)];
            hexChars[(j * 3 + 1)] = hexArray[(v & 0xF)];
            hexChars[(j * 3 + 2)] = ' ';
        }
        return new String(hexChars).trim();
    }
}