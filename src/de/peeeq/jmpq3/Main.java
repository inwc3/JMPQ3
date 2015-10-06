package de.peeeq.jmpq3;

import java.io.File;
import java.io.IOException;

public class Main {

	public static void main(String[] args) throws JMpqException, IOException, InterruptedException {
		// before 118.052 bytes

		
		JMpqEditor e = new JMpqEditor(new File("my.mpq")); 		//Opens a new editor
		e.deleteFile("filename");								//Deletes a specific file out of the mpq
		e.extractFile("filename", new File("target location"));	//Extracts a specific file out of the mpq to the target location			
		e.insertFile("filename", new File("file to add"), true);//Inserts a specific into the mpq from the target location	
		e.extractAllFiles(new File("target folder"));			//Extracts all files out of the mpq to the target folder
		e.getFileNames();										//Get the listfile as java List<String>
		e.close();												//Rebuilds the mpq and applies all changes which was made
		
//		e.extractFile("Abilities\\Spells\\Other\\Transmute\\Sparkle_Anim128.blp", new File("test.blp"));
		// e.insertFile(new
		// File("C:\\Users\\Crigges\\Desktop\\WurstPack alt\\lep.txt"),
		// "lep.txt");
		// e.insertFile(new File("war3map.doo"), "war3map.doo");
		// new JMpqEditor(new File("testbuild.w3x")).extractFile("lep.txt", new
		// File("lep.txt"));
	}

}
