package systems.crigges.jmpq3test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.*;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * Created by Frotty on 06.03.2017.
 */
public class MpqTests {
    private static File[] files;
    private Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private static File[] getMpqs() throws IOException {
        File[] files = new File(MpqTests.class.getClassLoader().getResource("./mpqs/").getFile())
                .listFiles((dir, name) -> name.endsWith(".w3x") || name.endsWith("" + ".mpq"));
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                Path target = files[i].toPath().resolveSibling("file_" + i + ".mpq");
                files[i] = Files.copy(files[i].toPath(), target,
                        StandardCopyOption.REPLACE_EXISTING).toFile();
            }
        }
        MpqTests.files = files;
        return files;
    }

    @AfterMethod
    public static void clearFiles() throws IOException {
        if (files != null) {
            for (File file : files) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    private static File getFile(String name) {
        return new File(MpqTests.class.getClassLoader().getResource(name).getFile());
    }

    @Test
    public void cryptoTest() throws IOException {
        byte[] bytes = "Hello World!".getBytes();

        final ByteBuffer workBuffer = ByteBuffer.allocate(bytes.length);
        final MPQEncryption encryptor = new MPQEncryption(-1011927184, false);
        encryptor.processFinal(ByteBuffer.wrap(bytes), workBuffer);
        workBuffer.flip();
        encryptor.changeKey(-1011927184, true);
        encryptor.processSingle(workBuffer);
        workBuffer.flip();

        //Assert.assertTrue(Arrays.equals(new byte[]{-96, -93, 89, -50, 43, -60, 18, -33, -31, -71, -81, 86}, a));
        //Assert.assertTrue(Arrays.equals(new byte[]{2, -106, -97, 38, 5, -82, -88, -91, -6, 63, 114, -31}, b));
        Assert.assertTrue(Arrays.equals(bytes, workBuffer.array()));
    }

    @Test
    public void hashTableTest() throws IOException {
        // get real example file paths
        final InputStream listFileFile = getClass().getClassLoader().getResourceAsStream("DefaultListfile.txt");
        final Scanner listFile = new Scanner(listFileFile);

        final String fp1 = listFile.nextLine();
        final String fp2 = listFile.nextLine();

        // small test hash table
        final HashTable ht = new HashTable(8);
        final short defaultLocale = HashTable.DEFAULT_LOCALE;
        final short germanLocale = 0x407;
        final short frenchLocale = 0x40c;
        final short russianLocale = 0x419;

        // assignment test
        ht.setFileBlockIndex(fp1, defaultLocale, 0);
        ht.setFileBlockIndex(fp2, defaultLocale, 1);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertEquals(ht.getFileBlockIndex(fp2, defaultLocale), 1);

        // deletion test
        ht.removeFile(fp2, defaultLocale);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertFalse(ht.hasFile(fp2));

        // locale test
        ht.setFileBlockIndex(fp1, germanLocale, 2);
        ht.setFileBlockIndex(fp1, frenchLocale, 3);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, defaultLocale), 0);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, germanLocale), 2);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, frenchLocale), 3);
        Assert.assertEquals(ht.getFileBlockIndex(fp1, russianLocale), 0);

        // file path deletion test
        ht.setFileBlockIndex(fp2, defaultLocale, 1);
        ht.removeFileAll(fp1);
        Assert.assertFalse(ht.hasFile(fp1));
        Assert.assertEquals(ht.getFileBlockIndex(fp2, defaultLocale), 1);

        // clean up
        listFile.close();
    }

    @Test
    public void testException() {
        Assert.expectThrows(JMpqException.class, () -> new BlockTable(ByteBuffer.wrap(new byte[0])).getBlockAtPos(-1));
    }

    @Test
    public void testRebuild() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            log.info(mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            if (mpqEditor.isCanWrite()) {
                mpqEditor.deleteFile("(listfile)");
            }
            mpqEditor.close(false, false, false);
        }
    }

    @Test
    public void testRecompressBuild() throws IOException {
        File[] mpqs = getMpqs();
        RecompressOptions options = new RecompressOptions(true);
        options.newSectorSizeShift = 15;
        for (File mpq : mpqs) {
            log.info(mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
            long length = mpq.length();
            options.useZopfli = !options.useZopfli;
            mpqEditor.close(true, true, options);
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
            log.info("test extract script: " + mpq.getName());
            JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
            File temp = File.createTempFile("war3mapj", "extracted", JMpqEditor.tempDir);
            temp.deleteOnExit();
            if (mpqEditor.hasFile("war3map.j")) {
                String extractedFile = mpqEditor.extractFileAsString("war3map.j").replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
                String existingFile = new String(Files.readAllBytes(getFile("war3map.j").toPath())).replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
                Assert.assertEquals(existingFile, extractedFile);
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
    public void testInsertByteArray() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            insertByteArrayAndVerify(mpq, "Example.txt");
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
            for (JMpqEditor mpqEditor1 : mpqEditors) {
                mpqEditor1.extractAllFiles(JMpqEditor.tempDir);
            }
            for (JMpqEditor mpqEditor : mpqEditors) {
                mpqEditor.close();
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

    private void insertByteArrayAndVerify(File mpq, String filename) throws IOException {
        String hashBefore;
        byte[] bytes;

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            File file = getFile(filename);
            hashBefore = TestHelper.md5(mpq);
            bytes = Files.readAllBytes(file.toPath());
            mpqEditor.insertByteArray(filename, Files.readAllBytes(getFile(filename).toPath()));
        }

        try (JMpqEditor mpqEditor = verifyMpq(mpq, filename, hashBefore, bytes)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }

    }

    private JMpqEditor verifyMpq(File mpq, String filename, String hashBefore, byte[] bytes) throws IOException {
        String hashAfter = TestHelper.md5(mpq);
        // If this fails, the mpq is not changed by the insert file command and something went wrong
        Assert.assertNotEquals(hashBefore, hashAfter);

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.hasFile(filename));
            byte[] bytes2 = mpqEditor.extractFileAsBytes(filename);
            Assert.assertEquals(bytes, bytes2);
            mpqEditor.deleteFile(filename);
        }

        return new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
    }

    private void insertAndVerify(File mpq, String filename) throws IOException {
        String hashBefore;
        byte[] bytes;
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            File file = getFile(filename);
            hashBefore = TestHelper.md5(mpq);
            bytes = Files.readAllBytes(file.toPath());
            mpqEditor.insertFile(filename, getFile(filename), false);
        }

        try (JMpqEditor mpqEditor = verifyMpq(mpq, filename, hashBefore, bytes)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }
    }

    private void insertAndDelete(File mpq, String filename) throws IOException {
        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            if (!mpqEditor.isCanWrite()) {
                return;
            }
            Assert.assertFalse(mpqEditor.hasFile(filename));
            String hashBefore = TestHelper.md5(mpq);
            mpqEditor.insertFile(filename, getFile(filename), true);
            mpqEditor.deleteFile(filename);
            mpqEditor.insertFile(filename, getFile(filename), false);
            mpqEditor.close();

            String hashAfter = TestHelper.md5(mpq);
            // If this fails, the mpq is not changed by the insert file command and something went wrong
            Assert.assertNotEquals(hashBefore, hashAfter);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(mpqEditor.hasFile(filename));

            mpqEditor.deleteFile(filename);
        }

        try (JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(mpqEditor.hasFile(filename));
        }
    }

    @Test(enabled = false)
    public void testRemoveHeaderoffset() throws IOException {
        File[] mpqs = getMpqs();
        File mpq = null;
        for (File mpq1 : mpqs) {
            if (mpq1.getName().startsWith("normal")) {
                mpq = mpq1;
                break;
            }
        }
        Assert.assertNotNull(mpq);

        log.info(mpq.getName());
        JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
        mpqEditor.setKeepHeaderOffset(false);
        mpqEditor.close();
        byte[] bytes = new byte[4];
        new FileInputStream(mpq).read(bytes);
        ByteBuffer order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(order.getInt(), JMpqEditor.ARCHIVE_HEADER_MAGIC);

        mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);
        Assert.assertTrue(mpqEditor.isCanWrite());
        mpqEditor.close();
    }

    private Set<File> getFiles(File dir) {
        Set<File> ret = new LinkedHashSet<>();

        for (File file : dir.listFiles()) {
            if (file.isDirectory()) ret.addAll(getFiles(file)); else ret.add(file);
        }

        return ret;
    }

    @Test()
    public void newBlocksizeBufferOverflow() throws IOException {
        File mpq = new File(MpqTests.class.getClassLoader().getResource("newBlocksizeBufferOverflow/mpq/newBlocksizeBufferOverflow.w3x").getFile());

        File targetMpq = mpq.toPath().resolveSibling("file1.mpq").toFile();

        targetMpq.delete();

        Files.copy(mpq.toPath(), targetMpq.toPath(), StandardCopyOption.REPLACE_EXISTING).toFile();

        mpq = targetMpq;

        String resourceDir = "newBlocksizeBufferOverflow/insertions";

        Set<File> files = getFiles(new File(MpqTests.class.getClassLoader().getResource("./" + resourceDir + "/").getFile()));

        JMpqEditor mpqEditor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0);

        for (File file : files) {
            String inName = file.toString().substring(file.toString().lastIndexOf(resourceDir) + resourceDir.length() + File.separator.length());

            mpqEditor.insertFile(inName, file, false);
            mpqEditor.insertFile(inName, file, false);
        }

        mpqEditor.close();
    }
}
