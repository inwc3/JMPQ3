package systems.crigges.jmpq3test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.testng.annotations.Test;

import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

public class ObjModHeaderTest {
	@Test
	public void test() throws Exception {
		File objFile = new File(ObjModHeaderTest.class.getClassLoader().getResource("./objmod_war3map.w3u").getFile());
		File mapFile = new File(ObjModHeaderTest.class.getClassLoader().getResource("./objmod_map.w3x").getFile());
		
		File mapOutFile = new File(ObjModHeaderTest.class.getClassLoader().getResource("").getFile(), "objmod_map_out.w3x");

		System.out.println(objFile + ";" + objFile.exists());
		System.out.println(mapFile + ";" + mapFile.exists());
		System.out.println(mapOutFile + ";" + mapOutFile.exists());
		
		Files.copy(mapFile.toPath(), mapOutFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		
		System.out.println("insert:");
		
		ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(objFile.toPath()));
		
		for (byte b : buf.array()) {
			System.out.println(b);
		}
		
		JMpqEditor editor = new JMpqEditor(mapOutFile, MPQOpenOption.FORCE_V0);
		
		editor.insertFile("war3map.w3u", objFile, false);
		
		editor.close();
		
		JMpqEditor editor2 = new JMpqEditor(mapOutFile, MPQOpenOption.FORCE_V0);
		
		System.out.println("extract:");
		
		ByteBuffer buf2 = ByteBuffer.wrap(editor2.extractFileAsBytes("war3map.w3u"));
		
		for (byte b : buf2.array()) {
			System.out.println(b);
		}
		
		editor2.close();
		
		if (!buf.equals(buf2)) {
			throw new Exception("insert buf != extract buf");
		}
	}
}
