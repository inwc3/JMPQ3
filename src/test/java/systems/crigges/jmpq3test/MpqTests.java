package systems.crigges.jmpq3test;

import org.junit.Assert;
import org.junit.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.JMpqException;

import java.io.File;
import java.io.IOException;

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
    public void testHasScriptFile() throws JMpqException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            JMpqEditor mpqEditor = new JMpqEditor(mpq);
            Assert.assertTrue(mpqEditor.hasFile("war3map.j"));
        }
    }

//    @Test
//    public void testExtractScriptFile() throws IOException {
//        File[] mpqs = getMpqs();
//        for (File mpq : mpqs) {
//            JMpqEditor mpqEditor = new JMpqEditor(mpq);
//            File temp = File.createTempFile("war3mapj", "extracted");
//            temp.deleteOnExit();
//            mpqEditor.extractFile("war3map.j", temp);
//            Assert.assertTrue(Arrays.equals(Files.readAllBytes(temp.toPath()), Files.readAllBytes(getFile("war3map.j").toPath())));
//        }
//    }

    @Test
    public void testInsertDeleteFile() throws IOException {
        File[] mpqs = getMpqs();
        for (File mpq : mpqs) {
            insertAndDelete(mpq, "Example.txt");
        }
    }

    private void insertAndDelete(File mpq, String filename) throws IOException {
        JMpqEditor mpqEditor = new JMpqEditor(mpq);
        Assert.assertFalse(mpqEditor.hasFile(filename));

        mpqEditor.insertFile(filename, getFile(filename), false);
        mpqEditor.close();
        mpqEditor = new JMpqEditor(mpq);
        Assert.assertTrue(mpqEditor.hasFile(filename));

        mpqEditor.deleteFile(filename);
        mpqEditor.close();
        mpqEditor = new JMpqEditor(mpq);
        Assert.assertFalse(mpqEditor.hasFile(filename));
    }


}
