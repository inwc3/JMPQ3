/*
 * 
 */
package systems.crigges.jmpq3;

import systems.crigges.jmpq3.BlockTable.Block;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

// TODO: Auto-generated Javadoc
/**
 * The Class JMpqEditor.
 *
 * @author peq and Crigges Some basic basic pure java based mpq implementation to
 *         open and modify warcraft 3 archives. Any bugs report here:
 *         https://github.com/Crigges/JMpq-v2/issues/new
 */
public class JMpqEditor implements AutoCloseable{
	
	/** The fc. */
	private FileChannel fc;
	
	/** The mpq file. */
	private File mpqFile;
	
	/** The header offset. */
	private int headerOffset = -1;
	
	/** The header size. */
	// Header
	private int headerSize;
	
	/** The archive size. */
	private int archiveSize;
	
	/** The format version. */
	private int formatVersion;
	
	/** The disc block size. */
	private int discBlockSize;
	
	/** The hash pos. */
	private int hashPos;
	
	/** The block pos. */
	private int blockPos;
	
	/** The hash size. */
	private int hashSize;
	
	/** The block size. */
	private int blockSize;

	/** The hash table. */
	private HashTable hashTable;
	
	/** The block table. */
	private BlockTable blockTable;
	
	/** The list file. */
	private Listfile listFile;
	
	/** The internal filename. */
	private HashMap<File, String> internalFilename = new HashMap<>();

	/** The files to add. */
	//BuildData
	private ArrayList<File> filesToAdd = new ArrayList<>();
	
	/** The keep header offset. */
	private boolean keepHeaderOffset = true;
	
	/** The new header size. */
	private int newHeaderSize;
	
	/** The new archive size. */
	private int newArchiveSize;
	
	/** The new format version. */
	private int newFormatVersion;
	
	/** The new disc block size. */
	private int newDiscBlockSize;
	
	/** The new hash pos. */
	private int newHashPos;
	
	/** The new block pos. */
	private int newBlockPos;
	
	/** The new hash size. */
	private int newHashSize;
	
	/** The new block size. */
	private int newBlockSize;
	
	/**
	 * Creates a new editor by parsing an exisiting mpq.
	 *
	 * @param mpqW the mpq w
	 * @throws JMpqException             if mpq is damaged or not supported
	 */
	public JMpqEditor(File mpqW) throws JMpqException {
		this.mpqFile = mpqW;
		try {
			long kbSize = mpqW.length() / 1024;
			//TODO fix this bad workaround
			File tempMpq = File.createTempFile("work", "around");
			tempMpq.deleteOnExit();
			Files.copy(mpqW.toPath(), tempMpq.toPath(), StandardCopyOption.REPLACE_EXISTING);
			
			fc = FileChannel.open(tempMpq.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
			
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

			if(hasFile("(listfile)")){
				try{
					File tempFile = File.createTempFile("list", "file");
					extractFile("(listfile)", tempFile);
					listFile = new Listfile(Files.readAllBytes(tempFile.toPath()));
				}catch (IOException e) {
					loadDefaultListFile();
				}
			}else {
                loadDefaultListFile();
            }
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

    private void loadDefaultListFile() throws IOException {
        Path defaultListfile = new File(getClass().getClassLoader().getResource("DefaultListfile.txt").getFile()).toPath();
        listFile = new Listfile(Files.readAllBytes(defaultListfile));
    }

    /**
	 * Search header.
	 *
	 * @return the int
	 * @throws JMpqException the j mpq exception
	 */
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
	
	/**
	 * Read header.
	 *
	 * @param buffer the buffer
	 */
	private void readHeader(MappedByteBuffer buffer){
		archiveSize = buffer.getInt();
		formatVersion = buffer.getShort();
		discBlockSize = 512 * (1 << buffer.getShort());
		hashPos = buffer.getInt();
		blockPos = buffer.getInt();
		hashSize = buffer.getInt();
		blockSize = buffer.getInt();
	}
	
	/**
	 * Write header.
	 *
	 * @param buffer the buffer
	 */
	private void writeHeader(MappedByteBuffer buffer){
		buffer.putInt(newHeaderSize);
		buffer.putInt(newArchiveSize);
		buffer.putShort((short) newFormatVersion);
		buffer.putShort((short) 3);
		buffer.putInt(newHashPos);
		buffer.putInt(newBlockPos);
		buffer.putInt(newHashSize);
		buffer.putInt(newBlockSize);
	}
	
	/**
	 * Calc new table size.
	 */
	private void calcNewTableSize(){
		int target = listFile.getFiles().size() + 1;
		int current = 2;
		while(current < target){
			current *= 2;
		}
		newHashSize = current;
		newBlockSize = listFile.getFiles().size() + 1;
	}
	
	
	/**
	 * Prints the header.
	 */
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
	
	/**
	 * Extract all files.
	 *
	 * @param dest the dest
	 * @throws JMpqException the j mpq exception
	 */
	public void extractAllFiles(File dest) throws JMpqException {
		if(!dest.isDirectory()){
			throw new JMpqException("Destination location isn't a directory");
		}
		if(listFile != null){
			for(String s : listFile.getFiles()){
				File temp = new File(dest.getAbsolutePath() + "\\" + s);
				temp.getParentFile().mkdirs();
				extractFile(s, temp);
			}
		}else{
			ArrayList<Block> blocks = blockTable.getAllVaildBlocks();
			try{
				int i = 0;
				for(Block b : blocks){
					if((b.getFlags() & MpqFile.ENCRYPTED) == MpqFile.ENCRYPTED){
						continue;
					}
					MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
					buf.order(ByteOrder.LITTLE_ENDIAN);
					MpqFile f = new MpqFile(buf , b, discBlockSize, "");
					f.extractToFile(new File(dest.getAbsolutePath() + "\\" + i));
					i++;
				}
			}catch (IOException e) {
				throw new JMpqException(e);
			}
		}
	}
	
	/**
	 * Gets the total file count.
	 *
	 * @return the total file count
	 * @throws JMpqException the j mpq exception
	 */
	public int getTotalFileCount() throws JMpqException{
		return blockTable.getAllVaildBlocks().size();
	}

	/**
	 * Extracts the specified file out of the mpq to the target location.
	 *
	 * @param name            name of the file
	 * @param dest            destination to that the files content is written
	 * @throws JMpqException             if file is not found or access errors occur
	 */
	public void extractFile(String name, File dest) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, name);
			f.extractToFile(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}
	
	/**
	 * Checks for file.
	 *
	 * @param name the name
	 * @return true, if successful
	 */
	public boolean hasFile(String name){
		try {
			hashTable.getBlockIndexOfFile(name);
		} catch (IOException e) {
			return false;
		}
		return true;
	}
	
	/**
	 * Gets the file names.
	 *
	 * @return the file names
	 */
	@SuppressWarnings("unchecked")
	public List<String> getFileNames(){
		return (List<String>) listFile.getFiles().clone();
	}
	
	/**
	 * Extracts the specified file out of the mpq and writes it to the target outputstream.
	 *
	 * @param name            name of the file
	 * @param dest            the outputstream where the file's content is written
	 * @throws JMpqException             if file is not found or access errors occur
	 */
	public void extractFile(String name, OutputStream dest) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, name);
			f.extractToOutputStream(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}	
	}
	
	/**
	 * Gets the mpq file.
	 *
	 * @param name the name
	 * @return the mpq file
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public MpqFile getMpqFile(String name) throws IOException{
		int pos = hashTable.getBlockIndexOfFile(name);
		Block b = blockTable.getBlockAtPos(pos);
		MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		return new MpqFile(buf , b, discBlockSize, name);
	}
	
	
	/**
	 * Deletes the specified file out of the mpq once you rebuild the mpq.
	 *
	 * @param name            of the file inside the mpq
	 * @throws JMpqException             if file is not found or access errors occur
	 */
	public void deleteFile(String name) throws JMpqException {
		listFile.removeFile(name);
	}

	/**
	 * Inserts the specified file into the mpq once you close the editor.
	 *
	 * @param name 			of the file inside the mpq
	 * @param f the f
	 * @param backupFile 			if true the editors creates a copy of the file to add, so 
	 * 			further changes won't affect the resulting mpq
	 * @throws JMpqException             if file is not found or access errors occur
	 */
	public void insertFile(String name, File f, boolean backupFile) throws JMpqException {
		try {
			listFile.addFile(name);
			if(backupFile){
				File temp = File.createTempFile("wurst", "crig");
				temp.deleteOnExit();
				Files.copy(f.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
				filesToAdd.add(temp);
				internalFilename.put(temp, name);
			}else{
				filesToAdd.add(f);
				internalFilename.put(f, name);
			}
		} catch (IOException e) {
			throw new JMpqException(e);
		}	
	}
	
	public void close() throws IOException{
		if(listFile == null){
			fc.close();
			return;
		}
		File temp = File.createTempFile("crig", "mpq");
        temp.deleteOnExit();
		FileChannel writeChannel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
		
		if(keepHeaderOffset){
			MappedByteBuffer headerReader = fc.map(MapMode.READ_ONLY, 0, headerOffset + 4);
			writeChannel.write(headerReader);
		}
		
		newHeaderSize = headerSize;
		newFormatVersion = formatVersion;
		newDiscBlockSize = discBlockSize;
		calcNewTableSize();
		
		ArrayList<Block> newBlocks = new ArrayList<>();
		ArrayList<String> newFiles = new ArrayList<>();
		@SuppressWarnings("unchecked")
		LinkedList<String> remainingFiles = (LinkedList<String>) listFile.getFiles().clone();
		int currentPos = headerOffset + headerSize;// + newHashSize * 16 + newBlockSize * 16;
		for(File f : filesToAdd){
			newFiles.add(internalFilename.get(f));
			remainingFiles.remove(internalFilename.get(f));
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, f.length() * 2);
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
			newBlocks.add(newBlock);
			MpqFile.writeFileAndBlock(f, newBlock, fileWriter, newDiscBlockSize);
			currentPos += newBlock.getCompressedSize();
		}
		for(String s : remainingFiles){
			newFiles.add(s);
			int pos = hashTable.getBlockIndexOfFile(s);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, s);
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, b.getCompressedSize());
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
			newBlocks.add(newBlock);
			f.writeFileAndBlock(newBlock, fileWriter);
			currentPos += b.getCompressedSize();
		}
		newFiles.add("(listfile)");
		byte[] listfileArr = listFile.asByteArray();
		MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, listfileArr.length);
		Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
		newBlocks.add(newBlock);
		MpqFile.writeFileAndBlock(listfileArr, newBlock, fileWriter, newDiscBlockSize);
		currentPos += newBlock.getCompressedSize();
		
		newHashPos = currentPos - headerOffset;
		newBlockPos = newHashPos + newHashSize * 16;
		
		MappedByteBuffer hashtableWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, newHashSize * 16);
		hashtableWriter.order(ByteOrder.LITTLE_ENDIAN);
		HashTable.writeNewHashTable(newHashSize, newFiles, hashtableWriter);
		currentPos += newHashSize * 16;
		
		MappedByteBuffer blocktableWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, newBlockSize * 16);
		blocktableWriter.order(ByteOrder.LITTLE_ENDIAN);
		BlockTable.writeNewBlocktable(newBlocks, newBlockSize, blocktableWriter);
		currentPos += newBlockSize * 16;
		
		newArchiveSize = currentPos + 1 - headerOffset;
		
		MappedByteBuffer headerWriter = writeChannel.map(MapMode.READ_WRITE, headerOffset + 4, headerSize + 4);
		headerWriter.order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(headerWriter);
		
		MappedByteBuffer tempReader = writeChannel.map(MapMode.READ_WRITE, 0, currentPos + 1);
		tempReader.position(0);
		
		mpqFile.delete();
		FileOutputStream out = new FileOutputStream(mpqFile);
		WritableByteChannel ch = Channels.newChannel(out);
		ch.write(tempReader);
		tempReader.position(tempReader.position() - 1);
		ch.write(tempReader);
		ch.close();
		out.close();

//		FileChannel mpqChannel = FileChannel.open(mpqFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
//		mpqChannel.truncate(currentPos + 1);
//		MappedByteBuffer tempWriter = mpqChannel.map(MapMode.READ_WRITE, 0, currentPos + 1);
//		tempWriter.position(0);
//		tempWriter.put(tempReader);
		fc.close();
		writeChannel.close();
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize + ", formatVersion="
				+ formatVersion + ", discBlockSize=" + discBlockSize + ", hashPos=" + hashPos + ", blockPos="
				+ blockPos + ", hashSize=" + hashSize + ", blockSize=" + blockSize + ", hashMap=" + hashTable + "]";
	}
}
