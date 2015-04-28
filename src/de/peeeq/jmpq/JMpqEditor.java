package de.peeeq.jmpq;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;

import com.google.common.io.LittleEndianDataInputStream;

import de.peeeq.jmpq.BlockTable.Block;

/**
 * @author peq & Crigges Some basic basic pure java based mpq implementation to
 *         open and modify warcraft 3 archives. Any bugs report here:
 *         https://github.com/Crigges/JMpq-v2/issues/new
 */
public class JMpqEditor {
	private FileChannel fc;
	private File mpq;
	private int headerOffset = -1;
	// Header
	private int headerSize;
	private int archiveSize;
	private int formatVersion;
	private int discBlockSize;
	private int hashPos;
	private int blockPos;
	private int hashSize;
	private int blockSize;

	private HashTable hashTable;
	private BlockTable blockTable;
	private ArrayList<File> filesToAdd = new ArrayList<>();
	private Listfile listFile;
	private HashMap<String, MpqFile> filesByName = new HashMap<>();
	private boolean useBestCompression = false;
	private boolean readOnlyMode = false;
	
	/**
	 * Creates a new editor by parsing an exisiting mpq.
	 * 
	 * @param mpq
	 *            the mpq to parse
	 * @throws JMpqException
	 *             if mpq is damaged or not supported
	 * @throws IOException
	 *             if access problems occcur
	 */
	public JMpqEditor(File mpq) throws JMpqException {
		this.mpq = mpq;
		try {
			fc = FileChannel.open(mpq.toPath(), StandardOpenOption.CREATE, 
					StandardOpenOption.WRITE, StandardOpenOption.READ);
			
			headerOffset = searchHeader();
			
			MappedByteBuffer temp = fc.map(MapMode.READ_ONLY, headerOffset + 4, 4);
			temp.order(ByteOrder.LITTLE_ENDIAN);
			headerSize = temp.getInt();
			
			MappedByteBuffer headerBuffer = fc.map(MapMode.READ_ONLY, headerOffset + 8, headerSize);
			headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
			readHeader(headerBuffer);
			
			MappedByteBuffer hashBuffer = fc.map(MapMode.READ_ONLY, hashPos + headerOffset, hashSize * 16);
			hashBuffer.order(ByteOrder.LITTLE_ENDIAN);
			hashTable = new HashTable(hashBuffer);
			
			MappedByteBuffer blockBuffer = fc.map(MapMode.READ_ONLY, blockPos + headerOffset, blockSize * 16);
			blockBuffer.order(ByteOrder.LITTLE_ENDIAN);
			blockTable = new BlockTable(blockBuffer);
		
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}
	
	private int searchHeader() throws JMpqException{
		try {
			MappedByteBuffer buffer = fc.map(MapMode.READ_ONLY, 0, (fc.size() / 512) * 512);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			for(int i = 0; i < (fc.size() / 512); i++){
				buffer.position(i * 512);
				byte[] start = new byte[3];
				buffer.get(start);
				String s =  new String(start);
				if(s.equals("MPQ")){
					return buffer.position() - 3;
				}
			}
			throw new JMpqException("The given file is not a mpq or damaged");
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}
	
	private void readHeader(MappedByteBuffer buffer){
		archiveSize = buffer.getInt();
		formatVersion = buffer.getShort();
		discBlockSize = 512 * (1 << buffer.getShort());
		hashPos = buffer.getInt();
		blockPos = buffer.getInt();
		hashSize = buffer.getInt();
		blockSize = buffer.getInt();
	}
	
	public void printHeader(){
		System.out.println("Header offset: " + headerOffset);
		System.out.println("Archive size: " + archiveSize);
		System.out.println("Format version: " + formatVersion);
		System.out.println("Disc block size: " + discBlockSize);
		System.out.println("Hashtable position: " + hashPos);
		System.out.println("Blocktable position: " + blockPos);
		System.out.println("Hashtable size: " + hashSize);
		System.out.println("Blocktable size: " + blockSize);
	}

//	/**
//	 * Inserts a new file into the mpq with the specidied name. If a file
//	 * already exists it will get overwritten
//	 * 
//	 * @param source
//	 *            file which shold be inserted into the mpq
//	 * @param archiveName
//	 *            only use \ as file seperator / will fail
//	 * @throws JMpqException
//	 *             if file can't be read
//	 */
//	public void insertFile(File source, String archiveName) throws JMpqException {
//		if (readOnlyMode){
//			throw new JMpqException("Can't insert files in read only mode");
//		}
//		MpqFile f = new MpqFile(source, archiveName, discBlockSize);
//		listFile.addFile(archiveName);
//		filesByName.put(archiveName, f);
//	}
//
//	/**
//	 * Inserts a new file into the mpq with the specidied name. If a file
//	 * already exists it will get overwritten
//	 * 
//	 * @param source
//	 *            as byte array
//	 * @param archiveName
//	 *            only use \ as file seperator / will fail
//	 * @throws JMpqException
//	 */
//	public void insertFile(byte[] source, String archiveName) throws JMpqException {
//		if (readOnlyMode){
//			throw new JMpqException("Can't insert files in read only mode");
//		}
//		MpqFile f = new MpqFile(source, archiveName, discBlockSize);
//		listFile.addFile(archiveName);
//		filesByName.put(archiveName, f);
//	}
//
//	/**
//	 * Deletes the specified file from the mpq
//	 * 
//	 * @param name
//	 *            of the file, only use \ as file seperator / will fail
//	 * @throws JMpqException
//	 *             if file is not found
//	 */
//	public void deleteFile(String name) throws JMpqException {
//		if (readOnlyMode){
//			throw new JMpqException("Can't delete files in read only mode");
//		}
//		MpqFile f = filesByName.get(name);
//		if (f != null) {
//			listFile.removeFile(name);
//			filesByName.remove(name);
//		} else {
//			throw new JMpqException("Could not find file: " + name);
//		}
//	}
//
	/**
	 * Extracts the specified file out of the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void extractFile(String name, File dest) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size());
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, name);
			f.extractToFile(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
		
	}
	
	/**
	 * Deletes the specified file out of the mpq once you rebuild the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void deleteFile(String name) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			hashTable.deleteFile(name);
			blockTable.deleteBlockAtPos(pos);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
		
	}
	
	/**
	 * Inserts the specified file out of the mpq once you rebuild the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void insertFile(String name, File f) throws JMpqException {
		try {
			FileInputStream in = new FileInputStream(f);
			File temp = File.createTempFile(name, "crig");
			temp = new File("sample.txt");
			FileOutputStream out = new FileOutputStream(temp);
			int i = in.read();
			while(i != -1){
				out.write(i);
				i = in.read();
			}
			in.close();
			out.flush();
			out.close();
		} catch (IOException e) {
			throw new JMpqException(e);
		}
		
	}
//
//	/**
//	 * Extracts the specified file out of the mpq
//	 * 
//	 * @param name
//	 *            of the file
//	 * @return the file as byte array
//	 * @throws JMpqException
//	 *             if file is not found
//	 */
//	public byte[] extractFile(String name) throws JMpqException {
//		try {
//			MpqFile f = filesByName.get(name);
//			if (f != null) {
//				return f.asFileArray();
//			} else {
//				try {
//					MpqFile fil = new MpqFile(Arrays.copyOfRange(fileAsArray, headerOffset, fileAsArray.length),
//							blockTable.getBlockAtPos(hashTable.getBlockIndexOfFile(name)), discBlockSize, name);
//					return fil.asFileArray();
//				} catch (Exception e) {
//					throw new JMpqException("Could not find file: " + name);
//				}
//			}
//		} catch (IOException e) {
//			throw new JMpqException(e);
//		}
//	}
//
//	private String readString(DataInput reader, int size) throws IOException {
//		byte[] start = new byte[size];
//		reader.readFully(start);
//		String startString = new String(start);
//		return startString;
//	}
//
//	private void build(boolean bestCompression) throws JMpqException {
//		// Write start offset -> Caluclate header -> WriteFiles and save their
//		// offsets -> Generate Blocktable -> Generate Hastable -> Write
//		// HashTable -> Write BlockTable
//		boolean rebuild = bestCompression & discBlockSize != (512 * (1 << 10));
//		File temp;
//		try {
//			temp = File.createTempFile("war", "mpq");
//		} catch (IOException e) {
//			throw new JMpqException("Could not create buildfile, reason: " + e.getCause());
//		}
//		try (FileOutputStream out = new FileOutputStream(temp)) {
//			// Write start offset
//			out.write(fileAsArray, 0, headerOffset);
//			// Calculate Header
//			// Get Hash and Block Table Size
//			int lines = listFile.getFiles().size() + 1;
//			double helper = Math.log10(lines) / Math.log10(2);
//			int a = (int) (helper + 1);
//			int b = (int) (helper);
//			helper = Math.pow(2, a);
//			a = (int) helper;
//			helper = Math.pow(2, b);
//			b = (int) helper;
//			int ad = Math.abs(lines - a);
//			int bd = Math.abs(lines - b);
//			if (ad > bd) {
//				lines = b * 2;
//			} else {
//				lines = a * 2;
//			}
//			// Calculate Archive Size
//			filesByName.put("(listfile)", new MpqFile(listFile.asByteArray(), "(listfile)", discBlockSize));
//			archiveSize = lines * 8 * 4 + 32 + 512 + lines * 2;
//			LinkedList<MpqFile> files = new LinkedList<>();
//			for (String s : listFile.getFiles()) {
//				files.add(filesByName.get(s));
//			}
//			if (rebuild) {
//				LinkedList<MpqFile> tempList = files;
//				files = new LinkedList<>();
//				for (MpqFile f : tempList) {
//					// 2^10
//					files.add(new MpqFile(f.asFileArray(), f.getName(), 512 * (1 << 10)));
//				}
//			}
//			int offsetHelper = 0;
//			for (MpqFile f : files) {
//				archiveSize += f.getCompSize();
//				f.setOffset(offsetHelper + 32);
//				offsetHelper += f.getCompSize();
//			}
//			ByteBuffer buf = ByteBuffer.allocate(32);
//			buf.order(ByteOrder.LITTLE_ENDIAN);
//			buf.put(("MPQ" + ((char) 0x1A)).getBytes());
//			buf.putInt(headerSize);
//			buf.putInt(archiveSize);
//			buf.putShort((short) formatVersion);
//			if (files.getFirst().getSectorSize() == 512 * (1 << 10)) {
//				buf.putShort((short) 10);
//			} else {
//				buf.putShort((short) 3);
//			}
//			buf.putInt(offsetHelper + 32);
//			buf.putInt(offsetHelper + 32 + lines * 16);
//			buf.putInt(lines);
//			buf.putInt(lines);
//			buf.position(0);
//			byte[] tempHeader = new byte[32];
//			buf.get(tempHeader);
//			// Write header
//			out.write(tempHeader);
//			// Write file data
//			for (MpqFile f : files) {
//				byte[] arr = f.getFileAsByteArray((int) (out.getChannel().position() - 512));
//				out.write(arr);
//			}
//			// Generate BlockTable
//			BlockTable bt = new BlockTable(files, lines);
//			HashTable.writeNewHashTable(files, bt.ht, lines, out, hashTable);
//			bt.writeToFile(out);
//			for (int i = 1; i <= 1000; i++) {
//				out.write(0);
//			}
//
//		} catch (IOException e) {
//			throw new JMpqException("Could not write buildfile, reason: " + e.getCause());
//		}
//		try {
//			mpq.delete();
//			com.google.common.io.Files.copy(temp, mpq);
//		} catch (IOException e) {
//			throw new JMpqException("Could not overwrite the orginal mpq: " + e.getCause());
//		}
//	}
//
//	/**
//	 * Closes the mpq and write the changes to the file
//	 * 
//	 * @param bestCompression
//	 *            provides better compression when true, but may take more time
//	 * @throws JMpqException
//	 *             if an error while writing occurs
//	 */
//	public void close(boolean bestCompression) throws JMpqException {
//		if (readOnlyMode){
//			return;
//		}else{
//			build(bestCompression);
//		}
//	}
//
//	@Override
//	public String toString() {
//		return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize + ", formatVersion="
//				+ formatVersion + ", discBlockSize=" + discBlockSize + ", hashPos=" + hashPos + ", blockPos="
//				+ blockPos + ", hashSize=" + hashSize + ", blockSize=" + blockSize + ", hashMap=" + hashTable + "]";
//	}
//
//	@Override
//	public void close() throws JMpqException {
//		if (readOnlyMode){
//			return;
//		}else{
//			close(useBestCompression);
//		}
//	}
//
//	public void setUseBestCompression(boolean useBestCompression) {
//		this.useBestCompression = useBestCompression;
//	}
//
}
