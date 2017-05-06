package systems.crigges.jmpq3;

import systems.crigges.jmpq3.BlockTable.Block;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

import static systems.crigges.jmpq3.MpqFile.*;

/**
 * Provides an interface for using MPQ archive files. MPQ archive files contain
 * a virtual file system used by some old games to hold data, primarily those
 * from Blizzard Entertainment.
 * <p>
 * MPQ archives are not intended as a general purpose file system. File access
 * and reading is highly efficient. File manipulation and writing is not
 * efficient and may require rebuilding a large portion of the archive file.
 * Empty directories are not supported. The full contents of the archive might
 * not be discoverable, but such files can still be accessed if their full path
 * is known. File attributes are optional.
 * <p>
 * For platform independence the implementation is pure Java.
 */
public class JMpqEditor implements AutoCloseable {
	public static File tempDir;
	private AttributesFile attributes;
	/**
	 * The fc.
	 */
	private FileChannel fc;

	/**
	 * The header offset.
	 */
	private long headerOffset = -1;

	/**
	 * The header size.
	 */
	private int headerSize;

	/**
	 * The archive size.
	 */
	private long archiveSize;

	/**
	 * The format version.
	 */
	private int formatVersion;

	/**
	 * The disc block size.
	 */
	private int discBlockSize;

	/**
	 * The hash table file position.
	 */
	private long hashPos;

	/**
	 * The block table file position.
	 */
	private long blockPos;

	/**
	 * The hash size.
	 */
	private int hashSize;

	/**
	 * The block size.
	 */
	private int blockSize;

	/**
	 * The hash table.
	 */
	private HashTable hashTable;

	/**
	 * The block table.
	 */
	private BlockTable blockTable;

	/**
	 * The list file.
	 */
	private Listfile listFile;

	/**
	 * The internal filename.
	 */
	private HashMap<File, String> internalFilename = new HashMap<>();

	/**
	 * The files to add.
	 */
	// BuildData
	private ArrayList<File> filesToAdd = new ArrayList<>();

	/**
	 * The keep header offset.
	 */
	private boolean keepHeaderOffset = true;

	/**
	 * The new header size.
	 */
	private int newHeaderSize;

	/**
	 * The new archive size.
	 */
	private long newArchiveSize;

	/**
	 * The new format version.
	 */
	private int newFormatVersion;

	/**
	 * The new disc block size.
	 */
	private int newDiscBlockSize;

	/**
	 * The new hash pos.
	 */
	private long newHashPos;

	/**
	 * The new block pos.
	 */
	private long newBlockPos;

	/**
	 * The new hash size.
	 */
	private int newHashSize;

	/**
	 * The new block size.
	 */
	private int newBlockSize;

	/**
	 * If write operations are supported on the archive.
	 */
	private final boolean canWrite;

	/**
	 * MPQ format version 0 forced compatibility is being used.
	 */
	private final boolean legacyCompatibility;

	/**
	 * Creates a new MPQ editor for the MPQ file at the specified path.
	 * <p>
	 * If the archive file does not exist a new archive file will be
	 * created automatically. Any changes made to the archive might only
	 * propagate to the file system once this's close method is called.
	 * <p>
	 * When READ_ONLY option is specified then the archive file will never be
	 * modified by this editor.
	 *
	 * @param mpqArchive
	 *            path to a MPQ archive file.
	 * @param openOptions
	 *            options to use when opening the archive.
	 * @throws FileNotFoundException
	 *             if mpqArchive is not a file path or does not exist and
	 *             READ_ONLY option is specified.
	 * @throws JMpqException
	 *             if mpq is damaged or not supported.
	 */
	public JMpqEditor(Path mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
		// process open options
		canWrite = !Arrays.asList(openOptions).contains(MPQOpenOption.READ_ONLY);
		legacyCompatibility = Arrays.asList(openOptions).contains(MPQOpenOption.FORCE_V0);

		try {
			setupTempDir();

			final OpenOption[] fcOptions = canWrite ? new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE }
					: new OpenOption[] { StandardOpenOption.READ };
			fc = FileChannel.open(mpqArchive, fcOptions);

			headerOffset = searchHeader();

			// probe to sample file with
			ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

			// read header size
			fc.position(headerOffset + 4);
			readFully(probe, fc);
			headerSize = probe.getInt(0);
			if (legacyCompatibility) {
				// force version 0 header size
				headerSize = 32;
			} else if (headerSize < 32 || 208 < headerSize) {
				// header too small or too big
				throw new JMpqException("Bad header size.");
			}

			ByteBuffer headerBuffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
			readFully(headerBuffer, fc);
			headerBuffer.rewind();
			readHeader(headerBuffer);

			if (legacyCompatibility) {
				// limit end of archive by end of file
				archiveSize = Math.min(archiveSize, fc.size() - headerOffset);

				// limit block table size by end of archive
				blockSize = (int) (Math.min(blockSize, (archiveSize - blockPos) / 16));
			}

			ByteBuffer hashBuffer = ByteBuffer.allocate(hashSize * 16).order(ByteOrder.LITTLE_ENDIAN);
			fc.position(headerOffset + hashPos);
			readFully(hashBuffer, fc);
			hashBuffer.rewind();
			hashTable = new HashTable(hashBuffer);

			ByteBuffer blockBuffer = ByteBuffer.allocate(blockSize * 16).order(ByteOrder.LITTLE_ENDIAN);
			fc.position(headerOffset + blockPos);
			readFully(blockBuffer, fc);
			blockBuffer.rewind();
			blockTable = new BlockTable(blockBuffer);

			if (hasFile("(listfile)")) {
				try {
					File tempFile = File.createTempFile("list", "file", JMpqEditor.tempDir);
					tempFile.deleteOnExit();
					extractFile("(listfile)", tempFile);
					listFile = new Listfile(Files.readAllBytes(tempFile.toPath()));
				} catch (Exception e) {
					loadDefaultListFile();
				}
			} else {
				loadDefaultListFile();
			}

			if (hasFile("(attributes)")) {
				try {
					attributes = new AttributesFile(extractFileAsBytes("(attributes)"));
				} catch (Exception e) {
				}
			}
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	/**
     * Creates a new MPQ editor for the specified MPQ file.
     * <p>
     * If the archive file does not exist a new archive file will be created
     * automatically. Any changes made to the archive might only propagate to
     * the file system once this's close method is called.
     * <p>
     * When READ_ONLY option is specified then the archive file will never be
     * modified by this editor.
     *
     * @param mpqArchive
     *            a MPQ archive file.
     * @param openOptions
     *            options to use when opening the archive.
     * @throws FileNotFoundException
     *             if mpqArchive is not a file or does not exist and READ_ONLY
     *             option is specified.
     * @throws JMpqException
     *             if mpq is damaged or not supported.
     */
	public JMpqEditor(File mpqArchive, MPQOpenOption... openOptions) throws IOException {
		this(mpqArchive.toPath(), openOptions);
	}
	
	/**
	 * Creates a new MPQ editor for the specified MPQ format version 0 archive
	 * file.
	 * <p>
	 * If the archive file does not exist a new archive file will be created
	 * automatically. Any changes made to the archive might only propagate to
	 * the file system once this's close method is called.
	 * <p>
	 * This constructor is deprecated as it only allows the use of MPQ format
	 * version 0 archive files and does not allow archive files to be opened as
	 * read only. Similar behaviour can be obtained with other constructors by
	 * using MPQOpenOption.FORCE_V0.
	 *
	 * @param mpqArchive
	 *            a MPQ archive file.
	 * @throws JMpqException
	 *             if mpq is damaged or not supported.
	 */
	@Deprecated
	public JMpqEditor(File mpqArchive) throws IOException {
		this(mpqArchive.toPath(), MPQOpenOption.FORCE_V0);
	}

	private void setupTempDir() throws JMpqException {
		try {
			Path path = Paths.get(System.getProperty("java.io.tmpdir") + "jmpq");
			JMpqEditor.tempDir = path.toFile();
			if (!JMpqEditor.tempDir.exists())
				Files.createDirectory(path);

			File[] files = JMpqEditor.tempDir.listFiles();
			for (File f : files) {
				// Delete existing tempfiles that are older than 1 day
				if ((System.currentTimeMillis() - f.lastModified()) > 1000 * 60 * 60 * 24) {
					f.delete();
				}
			}
		} catch (IOException e) {
			try {
				JMpqEditor.tempDir = Files.createTempDirectory("jmpq").toFile();
			} catch (IOException e1) {
				throw new JMpqException(e1);
			}
		}
	}

	/**
	 * Loads a default listfile for mpqs that have none
	 *
	 * @throws IOException
	 */
	private void loadDefaultListFile() throws IOException {
		Path defaultListfile = new File(getClass().getClassLoader().getResource("DefaultListfile.txt").getFile()).toPath();
		listFile = new Listfile(Files.readAllBytes(defaultListfile));
	}

	/**
	 * Utility method to fill a buffer from the given channel.
	 * 
	 * @param buffer
	 *            buffer to fill.
	 * @param src
	 *            channel to fill from.
	 * @throws IOException
	 *             if an exception occurs when reading.
	 * @throws EOFException
	 *             if EoF is encountered before buffer is full or channel is non
	 *             blocking.
	 */
	private static void readFully(ByteBuffer buffer, ReadableByteChannel src) throws IOException {
		while (buffer.hasRemaining()) {
			if (src.read(buffer) < 1)
				throw new EOFException("Cannot read enough bytes.");
		}
	}

	private static final int ARCHIVE_HEADER_MAGIC = ByteBuffer.wrap(new byte[] { 'M', 'P', 'Q', 0x1A }).order(ByteOrder.LITTLE_ENDIAN).getInt();

	private static final int USER_DATA_HEADER_MAGIC = ByteBuffer.wrap(new byte[] { 'M', 'P', 'Q', 0x1B }).order(ByteOrder.LITTLE_ENDIAN).getInt();

	/**
	 * Searches the file for the MPQ archive header.
	 *
	 * @return the file position at which the MPQ archive starts.
	 * @throws IOException
	 *             if an error occurs while searching.
	 * @throws JMpqException
	 *             if file does not contain a MPQ archive.
	 */
	private long searchHeader() throws IOException {
		// probe to sample file with
		ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

		final long fileSize = fc.size();
		for (long filePos = 0; filePos + probe.capacity() < fileSize; filePos += 0x200) {
			probe.rewind();
			fc.position(filePos);
			readFully(probe, fc);

			final int sample = probe.getInt(0);
			if (sample == ARCHIVE_HEADER_MAGIC) {
				// found archive header
				return filePos;
			} else if (sample == USER_DATA_HEADER_MAGIC && !legacyCompatibility) {
				// MPQ user data header with redirect to MPQ header
				// ignore in legacy compatibility mode

				// TODO process these in some meaningful way

				probe.rewind();
				fc.position(filePos + 8);
				readFully(probe, fc);

				// add header offset and align
				filePos += (probe.getInt(0) & 0xFFFFFFFFL);
				filePos &= ~(0x200 - 1);
			}
		}

		throw new JMpqException("No MPQ archive in file.");
	}

	/**
	 * Read the MPQ archive header from the header chunk.
	 *
	 * @param buffer
	 *            buffer containing the header chunk.
	 */
	private void readHeader(ByteBuffer buffer) {
		archiveSize = buffer.getInt() & 0xFFFFFFFFL;
		formatVersion = buffer.getShort();
		if (legacyCompatibility) {
			// force version 0 interpretation
			formatVersion = 0;
		}
		discBlockSize = 512 * (1 << buffer.getShort());
		hashPos = buffer.getInt() & 0xFFFFFFFFL;
		blockPos = buffer.getInt() & 0xFFFFFFFFL;
		hashSize = buffer.getInt();
		blockSize = buffer.getInt();

		// version 1 extension
		if (formatVersion >= 1) {
			// TODO add high block table support
			buffer.getLong();

			// high 16 bits of file pos
			hashPos |= (buffer.getShort() & 0xFFFFL) << 32;
			blockPos |= (buffer.getShort() & 0xFFFFL) << 32;
		}

		// version 2 extension
		if (formatVersion >= 2) {
			// 64 bit archive size
			archiveSize = buffer.getLong();

			// TODO add support for BET and HET tables
			buffer.getLong();
			buffer.getLong();
		}

		// version 3 extension
		if (formatVersion >= 3) {
			// TODO add support for compression and checksums
			buffer.getLong();
			buffer.getLong();
			buffer.getLong();
			buffer.getLong();
			buffer.getLong();

			buffer.getInt();
			final byte[] md5 = new byte[16];
			buffer.get(md5);
			buffer.get(md5);
			buffer.get(md5);
			buffer.get(md5);
			buffer.get(md5);
			buffer.get(md5);
		}
	}

	/**
	 * Write header.
	 *
	 * @param buffer
	 *            the buffer
	 */
	private void writeHeader(MappedByteBuffer buffer) {
		buffer.putInt(newHeaderSize);
		buffer.putInt((int) newArchiveSize);
		buffer.putShort((short) newFormatVersion);
		buffer.putShort((short) 3);
		buffer.putInt((int) newHashPos);
		buffer.putInt((int) newBlockPos);
		buffer.putInt(newHashSize);
		buffer.putInt(newBlockSize);

		// TODO add full write support for versions above 1
	}

	/**
	 * Calc new table size.
	 */
	private void calcNewTableSize() {
		int target = listFile.getFiles().size() + 2;
		int current = 2;
		while (current < target) {
			current *= 2;
		}
		newHashSize = current * 2;
		newBlockSize = listFile.getFiles().size() + 2;
	}

	/**
	 * Prints the header.
	 */
	public void printHeader() {
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
	 * @param dest
	 *            the dest
	 * @throws JMpqException
	 *             the j mpq exception
	 */
	public void extractAllFiles(File dest) throws JMpqException {
		if (!dest.isDirectory()) {
			throw new JMpqException("Destination location isn't a directory");
		}
		if (listFile != null) {
			for (String s : listFile.getFiles()) {
				System.out.println("extracting: " + s);
				File temp = new File(dest.getAbsolutePath() + "\\" + s);
				temp.getParentFile().mkdirs();
				extractFile(s, temp);
			}
			if (hasFile("(attributes)")) {
				File temp = new File(dest.getAbsolutePath() + "\\" + "(attributes)");
				extractFile("(attributes)", temp);
			}
			File temp = new File(dest.getAbsolutePath() + "\\" + "(listfile)");
			extractFile("(listfile)", temp);
		} else {
			ArrayList<Block> blocks = blockTable.getAllVaildBlocks();
			try {
				int i = 0;
				for (Block b : blocks) {
					if ((b.getFlags() & MpqFile.ENCRYPTED) == MpqFile.ENCRYPTED) {
						continue;
					}
					ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
					fc.position(headerOffset + b.getFilePos());
					readFully(buf, fc);
					buf.rewind();
					MpqFile f = new MpqFile(buf, b, discBlockSize, "");
					f.extractToFile(new File(dest.getAbsolutePath() + "\\" + i));
					i++;
				}
			} catch (IOException e) {
				throw new JMpqException(e);
			}
		}
	}

	/**
	 * Gets the total file count.
	 *
	 * @return the total file count
	 * @throws JMpqException
	 *             the j mpq exception
	 */
	public int getTotalFileCount() throws JMpqException {
		return blockTable.getAllVaildBlocks().size();
	}

	/**
	 * Extracts the specified file out of the mpq to the target location.
	 *
	 * @param name
	 *            name of the file
	 * @param dest
	 *            destination to that the files content is written
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void extractFile(String name, File dest) throws JMpqException {
		try {
			MpqFile f = getMpqFile(name);
			f.extractToFile(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	/**
	 * Extracts the specified file out of the mpq to the target location.
	 *
	 * @param name
	 *            name of the file
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public byte[] extractFileAsBytes(String name) throws JMpqException {
		try {
			MpqFile f = getMpqFile(name);
			return f.extractToBytes();
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	/**
	 * Extracts the specified file out of the mpq to the target location.
	 *
	 * @param name
	 *            name of the file
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public String extractFileAsString(String name) throws JMpqException {
		return new String(extractFileAsBytes(name));
	}

	/**
	 * Checks for file.
	 *
	 * @param name
	 *            the name
	 * @return true, if successful
	 */
	public boolean hasFile(String name) {
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
	public List<String> getFileNames() {
		return (List<String>) listFile.getFiles().clone();
	}

	/**
	 * Extracts the specified file out of the mpq and writes it to the target
	 * outputstream.
	 *
	 * @param name
	 *            name of the file
	 * @param dest
	 *            the outputstream where the file's content is written
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void extractFile(String name, OutputStream dest) throws JMpqException {
		try {
			MpqFile f = getMpqFile(name);
			f.extractToOutputStream(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	/**
	 * Gets the mpq file.
	 *
	 * @param name
	 *            the name
	 * @return the mpq file
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public MpqFile getMpqFile(String name) throws IOException {
		int pos = hashTable.getBlockIndexOfFile(name);
		Block b = blockTable.getBlockAtPos(pos);

		ByteBuffer buffer = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
		fc.position(headerOffset + b.getFilePos());
		readFully(buffer, fc);
		buffer.rewind();

		return new MpqFile(buffer, b, discBlockSize, name);
	}

	/**
	 * Deletes the specified file out of the mpq once you rebuild the mpq.
	 *
	 * @param name
	 *            of the file inside the mpq
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void deleteFile(String name) throws JMpqException {
		if (!canWrite) {
			throw new NonWritableChannelException();
		}

		listFile.removeFile(name);
	}

	/**
	 * Inserts the specified file into the mpq once you close the editor.
	 *
	 * @param name
	 *            of the file inside the mpq
	 * @param f
	 *            the f
	 * @param backupFile
	 *            if true the editors creates a copy of the file to add, so
	 *            further changes won't affect the resulting mpq
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void insertFile(String name, File f, boolean backupFile) throws JMpqException {
		if (!canWrite) {
			throw new NonWritableChannelException();
		}

		try {
			listFile.addFile(name);
			if (backupFile) {
				File temp = File.createTempFile("wurst", "crig", JMpqEditor.tempDir);
				temp.deleteOnExit();
				Files.copy(f.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
				filesToAdd.add(temp);
				internalFilename.put(temp, name);
			} else {
				filesToAdd.add(f);
				internalFilename.put(f, name);
			}
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	public void close() throws IOException {
		// only rebuild if allowed
		if (!canWrite) {
			fc.close();
			return;
		}

		long t = System.nanoTime();
		System.out.println("Building mpq");
		if (listFile == null) {
			fc.close();
			return;
		}
		File temp = File.createTempFile("crig", "mpq", JMpqEditor.tempDir);
		temp.deleteOnExit();
		FileChannel writeChannel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);

		if (keepHeaderOffset) {
			ByteBuffer headerReader = ByteBuffer.allocate((int) (headerOffset + 4)).order(ByteOrder.LITTLE_ENDIAN);
			fc.position(0);
			readFully(headerReader, fc);
			headerReader.rewind();
			writeChannel.write(headerReader);
		}

		newFormatVersion = formatVersion;
		switch (newFormatVersion) {
		case 0:
			newHeaderSize = 32;
			break;
		case 1:
			newHeaderSize = 44;
			break;
		case 2:
		case 3:
			newHeaderSize = 208;
			break;
		}
		newDiscBlockSize = discBlockSize;
		calcNewTableSize();

		ArrayList<Block> newBlocks = new ArrayList<>();
		ArrayList<String> newFiles = new ArrayList<>();
		ArrayList<String> remainingFiles = new ArrayList<>(listFile.getFiles());
		// Sort entries to preserve block table order
		remainingFiles.sort((o1, o2) -> {
			int pos1 = 999999999;
			int pos2 = 999999999;
			try {
				pos1 = hashTable.getBlockIndexOfFile(o1);
			} catch (IOException ignored) {
			}
			try {
				pos2 = hashTable.getBlockIndexOfFile(o2);
			} catch (IOException ignored) {
			}
			return pos1 - pos2;
		});
		System.out.println("Sorted blocks");
		if (attributes != null) {
			attributes.setNames(remainingFiles);
		}
		long currentPos = headerOffset + headerSize;// + newHashSize * 16 +
													// newBlockSize * 16;
		for (File f : filesToAdd) {
			remainingFiles.remove(internalFilename.get(f));
		}
		for (String s : remainingFiles) {
			newFiles.add(s);
			int pos = hashTable.getBlockIndexOfFile(s);
			Block b = blockTable.getBlockAtPos(pos);
			ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
			fc.position(headerOffset + b.getFilePos());
			readFully(buf, fc);
			buf.rewind();
			MpqFile f = new MpqFile(buf, b, discBlockSize, s);
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, b.getCompressedSize());
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, b.getFlags());
			newBlocks.add(newBlock);
			f.writeFileAndBlock(newBlock, fileWriter);
			currentPos += b.getCompressedSize();
		}
		System.out.println("Added existing files");
		HashMap<String, File> newFileMap = new HashMap<>();
		for (File f : filesToAdd) {
			newFiles.add(internalFilename.get(f));
			newFileMap.put(internalFilename.get(f), f);
			remainingFiles.remove(internalFilename.get(f));
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, f.length() * 2);
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
			newBlocks.add(newBlock);
			MpqFile.writeFileAndBlock(f, newBlock, fileWriter, newDiscBlockSize);
			currentPos += newBlock.getCompressedSize();
		}
		System.out.println("Added new files");
		// Add listfile
		newFiles.add("(listfile)");
		byte[] listfileArr = listFile.asByteArray();
		MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, listfileArr.length);
		Block newBlock = new Block(currentPos - headerOffset, 0, 0, EXISTS | COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
		newBlocks.add(newBlock);
		MpqFile.writeFileAndBlock(listfileArr, newBlock, fileWriter, newDiscBlockSize, "(listfile)");
		currentPos += newBlock.getCompressedSize();
		System.out.println("Added listfile");

		// if (attributes != null) {
		// newFiles.add("(attributes)");
		// // Only generate attributes file when there has been one before
		// AttributesFile attributesFile = new AttributesFile(newFiles.size());
		// // Generate new values
		// long time = (new Date().getTime() + 11644473600000L) * 10000L;
		// for (int i = 0; i < newFiles.size() - 1; i++) {
		// String name = newFiles.get(i);
		// int entry = attributes.getEntry(name);
		// if (newFileMap.containsKey(name)){
		// // new file
		// attributesFile.setEntry(i, getCrc32(newFileMap.get(name)), time);
		// }else if (entry >= 0) {
		// // has timestamp
		// attributesFile.setEntry(i, getCrc32(name),
		// attributes.getTimestamps()[entry]);
		// } else {
		// // doesnt have timestamp
		// attributesFile.setEntry(i, getCrc32(name), time);
		// }
		// }
		// // newfiles don't contain the attributes file yet, hence -1
		// System.out.println("added attributes");
		// byte[] attrArr = attributesFile.buildFile();
		// fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos,
		// attrArr.length);
		// newBlock = new Block(currentPos - headerOffset, 0, 0, EXISTS |
		// COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
		// newBlocks.add(newBlock);
		// MpqFile.writeFileAndBlock(attrArr, newBlock, fileWriter,
		// newDiscBlockSize, "(attributes)");
		// currentPos += newBlock.getCompressedSize();
		// }

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

		fc.position(0);
		fc.write(tempReader);
		fc.truncate(fc.position());

		fc.close();
		writeChannel.close();

		t = System.nanoTime() - t;
		System.out.println("Rebuild complete. Took: " + (t / 1000000) + "ms");
	}

	private CRC32 crc32 = new CRC32();

	private int getCrc32(String name) throws JMpqException {
		return getCrc32(extractFileAsBytes(name));
	}

	private int getCrc32(File file) throws IOException {
		return getCrc32(Files.readAllBytes(file.toPath()));
	}

	public int getCrc32(byte[] bytes) throws JMpqException {
		crc32.reset();
		crc32.update(bytes);
		return (int) crc32.getValue();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize + ", formatVersion=" + formatVersion + ", discBlockSize=" + discBlockSize
				+ ", hashPos=" + hashPos + ", blockPos=" + blockPos + ", hashSize=" + hashSize + ", blockSize=" + blockSize + ", hashMap=" + hashTable + "]";
	}
}
