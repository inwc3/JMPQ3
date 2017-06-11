package systems.crigges.jmpq3test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Created by Frotty on 11.06.2017.
 */
public class TestHelper {
    // stolen from http://stackoverflow.com/a/304350/303637
    public static String md5(File f) {
        try {
            byte[] buf = new byte[1024];
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(f);
                 DigestInputStream dis = new DigestInputStream(is, md);) {
                while (dis.read(buf) >= 0);
            }
            byte[] digest = md.digest();
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    // stolen from http://stackoverflow.com/a/9855338/303637
    final protected static char[] hexArray = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
