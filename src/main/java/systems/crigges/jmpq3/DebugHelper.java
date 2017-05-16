package systems.crigges.jmpq3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
            e.printStackTrace();
        }
        return outputStream.toByteArray();
    }
}