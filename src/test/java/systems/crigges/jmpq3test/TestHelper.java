package systems.crigges.jmpq3test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-digest helpers shared by the tests.
 */
public final class TestHelper {
    private TestHelper() {
    }

    /**
     * @param path file to digest.
     * @return lower case hex MD5 of the file content.
     */
    public static String md5(Path path) {
        final MessageDigest md = digest();
        final byte[] buf = new byte[8192];
        try (InputStream is = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(is, md)) {
            while (dis.read(buf) >= 0) {
                // Digest is updated as a side effect of reading.
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytesToHex(md.digest());
    }

    /**
     * @param f file to digest.
     * @return lower case hex MD5 of the file content.
     */
    public static String md5(File f) {
        return md5(f.toPath());
    }

    /**
     * @param data bytes to digest.
     * @return lower case hex MD5 of the given bytes.
     */
    public static String md5(byte[] data) {
        final MessageDigest md = digest();
        md.update(data);
        return bytesToHex(md.digest());
    }

    public static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the platform.", e);
        }
    }
}
