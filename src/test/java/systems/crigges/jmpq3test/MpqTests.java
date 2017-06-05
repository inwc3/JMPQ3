package systems.crigges.jmpq3test;

import com.esotericsoftware.minlog.Log;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqCrypto;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Created by Frotty on 06.03.2017.
 */
public class MpqTests {

    private static File[] getMpqs() {
        return new File(MpqTests.class.getClassLoader().getResource("./mpqs/").getFile()).listFiles((dir, name) -> name.endsWith(".w3x"));
    }

    private static File getFile(String name) {
        return new File(MpqTests.class.getClassLoader().getResource(name).getFile());
    }

    @Test
    public void cryptoTest() throws IOException {
        byte[] bytes = "Hello World!".getBytes();
        byte[] a = MpqCrypto.encryptMpqBlock(ByteBuffer.wrap(bytes), bytes.length, -1011927184);
        byte[] b = MpqCrypto.decryptBlock(ByteBuffer.wrap(a), bytes.length, -1011927184);

        Assert.assertTrue(Arrays.equals(new byte[]{-96, -93, 89, -50, 43, -60, 18, -33, -31, -71, -81, 86}, a));
        Assert.assertTrue(Arrays.equals(new byte[]{2, -106, -97, 38, 5, -82, -88, -91, -6, 63, 114, -31}, b));
    }

    @Test
    public void testRebuild() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            Log.info(mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            mpqEditor.close();
        }
    }

    @Test
    public void testRecompressBuild() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            Log.info(mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            long length = mpq.length();
            mpqEditor.close(true, true, true);
            long newlength = mpq.length();
            System.out.println("Size win: " + (length - newlength));
        }
    }

    @Test
    public void testExtractAll() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            File file = new File("out/");
            file.mkdirs();
            mpqEditor.extractAllFiles(file);
            mpqEditor.close();
        }
    }

    @Test
    public void testExtractScriptFile() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            Log.info("test extract script: " + mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            File temp = File.createTempFile("war3mapj", "extracted", JMpqEditor.tempDir);
            temp.deleteOnExit();
            if (mpqEditor.hasFile("war3map.j")) {
                String extractedFile = mpqEditor.extractFileAsString("war3map.j").replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
                String existingFile = new String(Files.readAllBytes(getFile("war3map.j").toPath())).replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
                Assert.assertTrue(extractedFile.equalsIgnoreCase(existingFile));
            }
            mpqEditor.close();
        }
    }

    @Test
    public void testInsertDeleteRegularFile() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            insertAndDelete(mpq, "Example.txt");
        }
    }

    @Test
    public void testInsertDeleteZeroLengthFile() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            insertAndDelete(mpq, "0ByteExample.txt");
        }
    }

    @Test
    public void testMultipleInstances() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            JMpqEditor mpqEditors[] = new JMpqEditor[]{new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0),
                    new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0),
                    new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)};
            for (int i = 0; i < mpqEditors.length; i++) {
                mpqEditors[i].extractAllFiles(JMpqEditor.tempDir);
            }
            for (int i = 0; i < mpqEditors.length; i++) {
                mpqEditors[i].close();
            }
        }
    }

    @Test
    public void testIncompressibleFile() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            insertAndVerify(mpq, "incompressible.w3u");
        }
    }

    private void insertAndVerify(File mpq, String filename) throws IOException {
        JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
        try {
            File file = getFile(filename);
            byte[] bytes = Files.readAllBytes(file.toPath());
            mpqEditor.insertFile(filename, getFile(filename), false);
            mpqEditor.close();
            mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            Assert.assertTrue(mpqEditor.hasFile(filename));
            byte[] bytes2 = mpqEditor.extractFileAsBytes(filename);
            Assert.assertEquals(bytes, bytes2);
            mpqEditor.deleteFile(filename);
            mpqEditor.close();
            mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            Assert.assertFalse(mpqEditor.hasFile(filename));
        } catch (NonWritableChannelException ignored) {
        }
    }

    private void insertAndDelete(File mpq, String filename) throws IOException {
        JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
        Assert.assertFalse(mpqEditor.hasFile(filename));
        try {
            mpqEditor.insertFile(filename, getFile(filename), true);
            mpqEditor.deleteFile(filename);
            mpqEditor.insertFile(filename, getFile(filename), false);
            mpqEditor.close();
            mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            Assert.assertTrue(mpqEditor.hasFile(filename));

            mpqEditor.deleteFile(filename);
            mpqEditor.close();
            mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            Assert.assertFalse(mpqEditor.hasFile(filename));
            mpqEditor.close();
        } catch (NonWritableChannelException ignored) {
        }
    }
}
