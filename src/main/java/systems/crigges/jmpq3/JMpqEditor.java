package systems.crigges.jmpq3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.util.*;

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
    private Logger log = LoggerFactory.getLogger(this.getClass().getName());
    public static final int ARCHIVE_HEADER_MAGIC = ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1A}).order(ByteOrder.LITTLE_ENDIAN).getInt();
    public static final int USER_DATA_HEADER_MAGIC = ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1B}).order(ByteOrder.LITTLE_ENDIAN).getInt();

    /**
     * Encryption key for hash table data.
     */
    private static final int KEY_HASH_TABLE;

    /**
     * Encryption key for block table data.
     */
    private static final int KEY_BLOCK_TABLE;

    static {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process("(hash table)");
        KEY_HASH_TABLE = hasher.getHash();
        hasher.reset();
        hasher.process("(block table)");
        KEY_BLOCK_TABLE = hasher.getHash();
    }

    public static File tempDir;
    private AttributesFile attributes;
    /** MPQ format version 0 forced compatibility is being used. */
    private final boolean legacyCompatibility;
    /** The fc. */
    private FileChannel fc;
    /** The header offset. */
    private long headerOffset = -1;
    /** The header size. */
    private int headerSize;
    /** The archive size. */
    private long archiveSize;
    /** The format version. */
    private int formatVersion;
    /** The sector size shift */
    private int sectorSizeShift;
    /** The disc block size. */
    private int discBlockSize;
    /** The hash table file position. */
    private long hashPos;
    /** The block table file position. */
    private long blockPos;
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
    private IdentityHashMap<ByteBuffer, String> internalFilename = new IdentityHashMap<>();
    /** The files to add. */
    // BuildData
    private ArrayList<ByteBuffer> filesToAdd = new ArrayList<>();
    /** The keep header offset. */
    private boolean keepHeaderOffset = true;
    /** The new header size. */
    private int newHeaderSize;
    /** The new archive size. */
    private long newArchiveSize;
    /** The new format version. */
    private int newFormatVersion;
    /** The new disc block size. */
    private int newSectorSizeShift;
    /** The new disc block size. */
    private int newDiscBlockSize;
    /** The new hash pos. */
    private long newHashPos;
    /** The new block pos. */
    private long newBlockPos;
    /** The new hash size. */
    private int newHashSize;
    /** The new block size. */
    private int newBlockSize;

    /** If write operations are supported on the archive. */
    private boolean canWrite;

    /**
     * Creates a new MPQ editor for the MPQ file at the specified path.
     * <p>
     * If the archive file does not exist a new archive file will be created
     * automatically. Any changes made to the archive might only propagate to
     * the file system once this's close method is called.
     * <p>
     * When READ_ONLY option is specified then the archive file will never be
     * modified by this editor.
     *
     * @param mpqArchive  path to a MPQ archive file.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if mpq is damaged or not supported.
     */
    public JMpqEditor(Path mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        // process open options
        canWrite = !Arrays.asList(openOptions).contains(MPQOpenOption.READ_ONLY);
        legacyCompatibility = Arrays.asList(openOptions).contains(MPQOpenOption.FORCE_V0);

        try {
            setupTempDir();

            final OpenOption[] fcOptions = canWrite ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE}
                    : new OpenOption[]{StandardOpenOption.READ};
            fc = FileChannel.open(mpqArchive, fcOptions);

            headerOffset = searchHeader();

            readHeaderSize();

            readHeader();

            checkLegacyCompat();

            readHashTable();

            readBlockTable();

            readListFile();

            readAttributesFile();
        } catch (IOException e) {
            throw new JMpqException(mpqArchive.toAbsolutePath().toString() + ": " + e.getMessage());
        }
    }

    /**
     * See {@link #JMpqEditor(Path, MPQOpenOption...)} }
     *
     * @param mpqArchive  a MPQ archive file.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if mpq is damaged or not supported.
     */
    public JMpqEditor(File mpqArchive, MPQOpenOption... openOptions) throws IOException {
        this(mpqArchive.toPath(), openOptions);
    }

    /**
     * See {@link #JMpqEditor(Path, MPQOpenOption...)} }
     * Kept for backwards compatibility, but deprecated
     *
     * @param mpqArchive a MPQ archive file.
     * @throws JMpqException if mpq is damaged or not supported.
     */
    @Deprecated
    public JMpqEditor(File mpqArchive) throws IOException {
        this(mpqArchive.toPath(), MPQOpenOption.FORCE_V0);
    }

    private void checkLegacyCompat() throws IOException {
        if (legacyCompatibility) {
            // limit end of archive by end of file
            archiveSize = Math.min(archiveSize, fc.size() - headerOffset);

            // limit block table size by end of archive
            blockSize = (int) (Math.min(blockSize, (archiveSize - blockPos) / 16));
        }
    }

    private void readAttributesFile() {
        if (hasFile("(attributes)")) {
            try {
                attributes = new AttributesFile(extractFileAsBytes("(attributes)"));
            } catch (Exception e) {
            }
        }
    }

    private void readListFile() throws IOException {
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
    }

    private void readBlockTable() throws IOException {
        ByteBuffer blockBuffer = ByteBuffer.allocate(blockSize * 16).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + blockPos);
        readFully(blockBuffer, fc);
        blockBuffer.rewind();
        blockTable = new BlockTable(blockBuffer);
    }

    private void readHashTable() throws IOException {
        // read hash table
        ByteBuffer hashBuffer = ByteBuffer.allocate(hashSize * 16);
        fc.position(headerOffset + hashPos);
        readFully(hashBuffer, fc);
        hashBuffer.rewind();

        // decrypt hash table
        final MPQEncryption decrypt = new MPQEncryption(KEY_HASH_TABLE, true);
        decrypt.processSingle(hashBuffer);
        hashBuffer.rewind();

        // create hash table
        hashTable = new HashTable(hashSize);
        hashTable.readFromBuffer(hashBuffer);
    }

    private void readHeaderSize() throws IOException {
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
     * Makes the archive readonly.
     */
    private void loadDefaultListFile() throws IOException {
        log.warn("The mpq doesn't come with a listfile so it cannot be rebuild");
        InputStream resource = getClass().getClassLoader().getResourceAsStream("DefaultListfile.txt");
        if (resource != null) {
            File tempFile = File.createTempFile("jmpq", "lf", tempDir);
            tempFile.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                //copy stream
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = resource.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            listFile = new Listfile(Files.readAllBytes(tempFile.toPath()));
            canWrite = false;
        }
    }


    /**
     * Searches the file for the MPQ archive header.
     *
     * @return the file position at which the MPQ archive starts.
     * @throws IOException   if an error occurs while searching.
     * @throws JMpqException if file does not contain a MPQ archive.
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
     */
    private void readHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(buffer, fc);
        buffer.rewind();

        archiveSize = buffer.getInt() & 0xFFFFFFFFL;
        formatVersion = buffer.getShort();
        if (legacyCompatibility) {
            // force version 0 interpretation
            formatVersion = 0;
        }

        sectorSizeShift = buffer.getShort();
        discBlockSize = 512 * (1 << sectorSizeShift);
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
     * @param buffer the buffer
     */
    private void writeHeader(MappedByteBuffer buffer) {
        buffer.putInt(newHeaderSize);
        buffer.putInt((int) newArchiveSize);
        buffer.putShort((short) newFormatVersion);
        buffer.putShort((short) newSectorSizeShift);
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
     * Extract all files.
     *
     * @param dest the dest
     * @throws JMpqException the j mpq exception
     */
    public void extractAllFiles(File dest) throws JMpqException {
        if (!dest.isDirectory()) {
            throw new JMpqException("Destination location isn't a directory");
        }
        if (hasFile("(listfile)") && listFile != null) {
            for (String s : listFile.getFiles()) {
                log.debug("extracting: " + s);
                File temp = new File(dest.getAbsolutePath() + "\\" + s);
                temp.getParentFile().mkdirs();
                if (hasFile(s)) {
                    // Prevent exception due to nonexistent listfile entries
                    try {
                        extractFile(s, temp);
                    } catch (JMpqException e) {
                        log.warn("File possibly corrupted and could not be extracted: " + s);
                    }
                }
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
     * @throws JMpqException the j mpq exception
     */
    public int getTotalFileCount() throws JMpqException {
        return blockTable.getAllVaildBlocks().size();
    }

    /**
     * Extracts the specified file out of the mpq to the target location.
     *
     * @param name name of the file
     * @param dest destination to that the files content is written
     * @throws JMpqException if file is not found or access errors occur
     */
    public void extractFile(String name, File dest) throws JMpqException {
        try {
            MpqFile f = getMpqFile(name);
            f.extractToFile(dest);
        } catch (Exception e) {
            throw new JMpqException(e);
        }
    }

    /**
     * Extracts the specified file out of the mpq to the target location.
     *
     * @param name name of the file
     * @throws JMpqException if file is not found or access errors occur
     */
    public byte[] extractFileAsBytes(String name) throws JMpqException {
        try {
            MpqFile f = getMpqFile(name);
            return f.extractToBytes();
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    public String extractFileAsString(String name) throws JMpqException {
        try {
            byte[] f = extractFileAsBytes(name);
            return new String(f);
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
    public List<String> getFileNames() {
        return new ArrayList<>(listFile.getFiles());
    }

    /**
     * Extracts the specified file out of the mpq and writes it to the target
     * outputstream.
     *
     * @param name name of the file
     * @param dest the outputstream where the file's content is written
     * @throws JMpqException if file is not found or access errors occur
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
     * @param name the name
     * @return the mpq file
     * @throws IOException Signals that an I/O exception has occurred.
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
     * @param name of the file inside the mpq
     * @throws JMpqException if file is not found or access errors occur
     */
    public void deleteFile(String name) {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }

        listFile.removeFile(name);
    }

    /**
     * Inserts the specified byte array into the mpq once you close the editor.
     *
     * @param name  of the file inside the mpq
     * @param input the input byte array
     * @throws JMpqException if file is not found or access errors occur
     */
    public void insertByteArray(String name, byte[] input) throws NonWritableChannelException {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }

        listFile.addFile(name);
        ByteBuffer data = ByteBuffer.wrap(input);
        filesToAdd.add(data);
        internalFilename.put(data, name);
    }

    /**
     * Inserts the specified file into the mpq once you close the editor.
     *
     * @param name       of the file inside the mpq
     * @param file       the file
     * @param backupFile if true the editors creates a copy of the file to add, so
     *                   further changes won't affect the resulting mpq
     * @throws JMpqException if file is not found or access errors occur
     */
    public void insertFile(String name, File file, boolean backupFile) throws IOException {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }

        try {
            listFile.addFile(name);
            if (backupFile) {
                File temp = File.createTempFile("jmpq", "backup", JMpqEditor.tempDir);
                temp.deleteOnExit();
                Files.copy(file.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(temp.toPath()));
                filesToAdd.add(data);
                internalFilename.put(data, name);
            } else {
                ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(file.toPath()));
                filesToAdd.add(data);
                internalFilename.put(data, name);
            }
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    public void close() throws IOException {
        close(true, true, false);
    }

    public void close(boolean buildListfile, boolean buildAttributes, boolean recompress) throws IOException {
        close(buildListfile, buildAttributes, new RecompressOptions(recompress));
    }

    /**
     * @param buildListfile   whether or not to add a (listfile) to this mpq
     * @param buildAttributes whether or not to add a (attributes) file to this mpq
     * @throws IOException
     */
    public void close(boolean buildListfile, boolean buildAttributes, RecompressOptions options) throws IOException {
        // only rebuild if allowed
        if (!canWrite || !fc.isOpen()) {
            fc.close();
            log.debug("closed readonly mpq.");
            return;
        }

        long t = System.nanoTime();
        log.debug("Building mpq");
        if (listFile == null) {
            fc.close();
            return;
        }
        File temp = File.createTempFile("jmpq", "temp", JMpqEditor.tempDir);
        temp.deleteOnExit();
        FileChannel writeChannel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);

        ByteBuffer headerReader = ByteBuffer.allocate((int) ((keepHeaderOffset ? headerOffset : 0) + 4)).order(ByteOrder.LITTLE_ENDIAN);
        fc.position((keepHeaderOffset ? 0 : headerOffset));
        readFully(headerReader, fc);
        headerReader.rewind();
        writeChannel.write(headerReader);

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
        newSectorSizeShift = options.recompress ? Math.min(options.newSectorSizeShift, 15) : sectorSizeShift;
        newDiscBlockSize = options.recompress ? 512 * (1 << newSectorSizeShift) : discBlockSize;
        calcNewTableSize();

        ArrayList<Block> newBlocks = new ArrayList<>();
        ArrayList<String> newFiles = new ArrayList<>();
        ArrayList<String> existingFiles = new ArrayList<>(listFile.getFiles());

        sortListfileEntries(existingFiles);

        log.debug("Sorted blocks");
        if (attributes != null) {
            attributes.setNames(existingFiles);
        }
        long currentPos = (keepHeaderOffset ? headerOffset : 0) + headerSize;

        for (ByteBuffer file : filesToAdd) {
            existingFiles.remove(internalFilename.get(file));
        }

        for (String existingName : existingFiles) {
            if (options.recompress && !existingName.endsWith("wav")) {
                ByteBuffer extracted = ByteBuffer.wrap(extractFileAsBytes(existingName));
                internalFilename.put(extracted, existingName);
                filesToAdd.add(extracted);
            } else {
                newFiles.add(existingName);
                int pos = hashTable.getBlockIndexOfFile(existingName);
                Block b = blockTable.getBlockAtPos(pos);
                ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
                fc.position(headerOffset + b.getFilePos());
                readFully(buf, fc);
                buf.rewind();
                MpqFile f = new MpqFile(buf, b, discBlockSize, existingName);
                MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, b.getCompressedSize());
                Block newBlock = new Block(currentPos - (keepHeaderOffset ? headerOffset : 0), 0, 0, b.getFlags());
                newBlocks.add(newBlock);
                f.writeFileAndBlock(newBlock, fileWriter);
                currentPos += b.getCompressedSize();
            }
        }
        log.debug("Added existing files");
        HashMap<String, ByteBuffer> newFileMap = new HashMap<>();
        for (ByteBuffer newFile : filesToAdd) {
            newFiles.add(internalFilename.get(newFile));
            newFileMap.put(internalFilename.get(newFile), newFile);
            MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, newFile.limit() * 2);
            Block newBlock = new Block(currentPos - (keepHeaderOffset ? headerOffset : 0), 0, 0, 0);
            newBlocks.add(newBlock);
            MpqFile.writeFileAndBlock(newFile.array(), newBlock, fileWriter, newDiscBlockSize, options);
            currentPos += newBlock.getCompressedSize();
            log.debug("Added file " + internalFilename.get(newFile));
        }
        log.debug("Added new files");
        if (buildListfile) {
            // Add listfile
            newFiles.add("(listfile)");
            byte[] listfileArr = listFile.asByteArray();
            MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, listfileArr.length * 2);
            Block newBlock = new Block(currentPos - (keepHeaderOffset ? headerOffset : 0), 0, 0, EXISTS | COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
            newBlocks.add(newBlock);
            MpqFile.writeFileAndBlock(listfileArr, newBlock, fileWriter, newDiscBlockSize, "(listfile)", options);
            currentPos += newBlock.getCompressedSize();
            log.debug("Added listfile");
        }
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

        newBlockSize = newBlocks.size();

        newHashPos = currentPos - (keepHeaderOffset ? headerOffset : 0);
        newBlockPos = newHashPos + newHashSize * 16;

        // generate new hash table
        final int hashSize = newHashSize;
        HashTable hashTable = new HashTable(hashSize);
        int blockIndex = 0;
        for (String file : newFiles) {
            hashTable.setFileBlockIndex(file, HashTable.DEFAULT_LOCALE, blockIndex++);
        }

        // prepare hashtable for writing
        final ByteBuffer hashTableBuffer = ByteBuffer.allocate(hashSize * 16);
        hashTable.writeToBuffer(hashTableBuffer);
        hashTableBuffer.flip();

        // encrypt hash table
        final MPQEncryption encrypt = new MPQEncryption(KEY_HASH_TABLE, false);
        encrypt.processSingle(hashTableBuffer);
        hashTableBuffer.flip();

        // write out hash table
        writeChannel.position(currentPos);
        writeFully(hashTableBuffer, writeChannel);
        currentPos = writeChannel.position();

        // write out block table
        MappedByteBuffer blocktableWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, newBlockSize * 16);
        blocktableWriter.order(ByteOrder.LITTLE_ENDIAN);
        BlockTable.writeNewBlocktable(newBlocks, newBlockSize, blocktableWriter);
        currentPos += newBlockSize * 16;

        newArchiveSize = currentPos + 1 - (keepHeaderOffset ? headerOffset : 0);

        MappedByteBuffer headerWriter = writeChannel.map(MapMode.READ_WRITE, (keepHeaderOffset ? headerOffset : 0) + 4, headerSize + 4);
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
        log.debug("Rebuild complete. Took: " + (t / 1000000) + "ms");
    }

    private void sortListfileEntries(ArrayList<String> remainingFiles) {
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
    }

    /**
     * Utility method to fill a buffer from the given channel.
     *
     * @param buffer buffer to fill.
     * @param src    channel to fill from.
     * @throws IOException  if an exception occurs when reading.
     * @throws EOFException if EoF is encountered before buffer is full or channel is non
     *                      blocking.
     */
    private static void readFully(ByteBuffer buffer, ReadableByteChannel src) throws IOException {
        while (buffer.hasRemaining()) {
            if (src.read(buffer) < 1)
                throw new EOFException("Cannot read enough bytes.");
        }
    }

    /**
     * Utility method to write out a buffer to the given channel.
     *
     * @param buffer buffer to write out.
     * @param dest   channel to write to.
     * @throws IOException if an exception occurs when writing.
     */
    private static void writeFully(ByteBuffer buffer, WritableByteChannel dest) throws IOException {
        while (buffer.hasRemaining()) {
            if (dest.write(buffer) < 1)
                throw new EOFException("Cannot write enough bytes.");
        }
    }

    /**
     * @return Whether the map can be modified or not
     */
    public boolean isCanWrite() {
        return canWrite;
    }

    /**
     * Whether or not to keep the data before the actual mpq in the file
     *
     * @param keepHeaderOffset
     */
    public void setKeepHeaderOffset(boolean keepHeaderOffset) {
        this.keepHeaderOffset = keepHeaderOffset;
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
