/*
 * 
 */
package systems.crigges.jmpq3test;

import java.io.File;
import java.io.IOException;

import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.JMpqException;

// TODO: Auto-generated Javadoc
/**
 * The Class Main.
 */
public class Main {

	/**
	 * The main method.
	 *
	 * @param args the arguments
	 * @throws JMpqException the j mpq exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws InterruptedException the interrupted exception
	 */
	public static void main(String[] args) throws JMpqException, IOException, InterruptedException {
//		// before 118.052 bytes
//
//		
//		JMpqEditor e = new JMpqEditor(new File("my.mpq")); 		//Opens a new editor
//		e.deleteFile("filename");								//Deletes a specific file out of the mpq
//		e.extractFile("filename", new File("target location"));	//Extracts a specific file out of the mpq to the target location			
//		e.insertFile("filename", new File("file to add"), true);//Inserts a specific into the mpq from the target location	
//		e.extractAllFiles(new File("target folder"));			//Extracts all files out of the mpq to the target folder
//		e.getFileNames();										//Get the listfile as java List<String>
//		e.close();												//Rebuilds the mpq and applies all changes which was made
		
		JMpqEditor e = new JMpqEditor(new File("newTestMap.w3x"));
		File f = new File("testfolder");
		f.mkdirs();
		e.extractAllFiles(f);
		e.insertFile("blurp.jar", new File("build.gradle"),false);
		e.close();
	}

}
