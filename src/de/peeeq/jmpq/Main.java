package de.peeeq.jmpq;

import java.io.File;
import java.io.IOException;

public class Main {

	public static void main(String[] args) throws JMpqException, IOException, InterruptedException {
		// before 118.052 bytes
		JMpqEditor e = new JMpqEditor(new File("testmap.w3x"));
		e.printHeader();
		e.extractFile("war3map.j", null);
		Thread.sleep(10000);
//		e.extractFile("Abilities\\Spells\\Other\\Transmute\\Sparkle_Anim128.blp", new File("test.blp"));
		// e.insertFile(new
		// File("C:\\Users\\Crigges\\Desktop\\WurstPack alt\\lep.txt"),
		// "lep.txt");
		// e.insertFile(new File("war3map.doo"), "war3map.doo");
		// new JMpqEditor(new File("testbuild.w3x")).extractFile("lep.txt", new
		// File("lep.txt"));
	}

}
