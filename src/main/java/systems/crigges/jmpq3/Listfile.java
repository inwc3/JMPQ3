/*
 * 
 */
package systems.crigges.jmpq3;

import java.util.LinkedList;
import java.util.Scanner;

// TODO: Auto-generated Javadoc
/**
 * The Class Listfile.
 */
public class Listfile {
	
	/** The files. */
	private LinkedList<String> files = new LinkedList<String>();

	/**
	 * Instantiates a new listfile.
	 *
	 * @param file the file
	 */
	public Listfile(byte[] file) {
		String list = new String(file);
		Scanner sc = new Scanner(list);
		while (sc.hasNextLine()) {
			files.add(sc.nextLine());
		}
		sc.close();
	}

	/**
	 * Gets the files.
	 *
	 * @return the files
	 */
	public LinkedList<String> getFiles() {
		return files;
	}

	/**
	 * Adds the file.
	 *
	 * @param name the name
	 */
	public void addFile(String name) {
		if (!files.contains(name)) {
			files.add(name);
		}
	}

	/**
	 * Removes the file.
	 *
	 * @param name the name
	 */
	public void removeFile(String name) {
		files.remove(name);
	}

	/**
	 * As byte array.
	 *
	 * @return the byte[]
	 */
	public byte[] asByteArray() {
		String temp = "";
		for (String s : files) {
			temp += s + "\n";
		}
		return temp.getBytes();
	}
}
