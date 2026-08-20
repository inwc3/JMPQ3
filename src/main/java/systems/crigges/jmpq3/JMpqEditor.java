package systems.crigges.jmpq3;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static systems.crigges.jmpq3.MpqFile.ADJUSTED_ENCRYPTED;
import static systems.crigges.jmpq3.MpqFile.COMPRESSED;
import static systems.crigges.jmpq3.MpqFile.ENCRYPTED;
import static systems.crigges.jmpq3.MpqFile.EXISTS;

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
 *
 * <h2>Thread safety</h2>
 * A single editor instance is <b>not</b> thread safe and must be confined to one
 * thread. Separate instances, however, are fully independent: as of 2.0 nothing
 * in the library holds global mutable state. Before 2.0, opening any archive
 * wiped a shared {@code %TMP%/jmpq} directory and the compression codecs were
 * static singletons, so concurrent use corrupted data.
 *
 * <h2>Rebuild model</h2>
 * A writable archive is rebuilt when {@link #close()} runs. The rebuild happens
 * entirely in memory and the finished image replaces the file in one write; no
 * temporary files are involved.
 */
public class JMpqEditor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JMpqEditor.class);

    public static final int ARCHIVE_HEADER_MAGIC =
        ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1A}).order(ByteOrder.LITTLE_ENDIAN).getInt();
    public static final int USER_DATA_HEADER_MAGIC =
        ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1B}).order(ByteOrder.LITTLE_ENDIAN).getInt();

    /** Header size for each format version, indexed by version. */
    private static final int[] HEADER_SIZES = {32, 44, 68, 208};

    /** Largest archive size a 32-bit header field can express. */
    private static final long V0_MAX_ARCHIVE_SIZE = 0xFFFFFFFFL;

    /** StormLib's {@code HASH_TABLE_SIZE_MAX}. */
    private static final int HASH_TABLE_SIZE_MAX = 0x00080000;

    /**
     * Largest sector size shift that keeps {@code 512 << shift} inside a
     * positive int.
     */
    private static final int MAX_SECTOR_SIZE_SHIFT = 21;

    /** Alignment of candidate archive header positions. */
    private static final int HEADER_ALIGNMENT = 0x200;

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

    private AttributesFile attributes;

    /**
     * MPQ format version 0 forced compatibility is being used.
     */
    private final boolean legacyCompatibility;

    /** The archive's backing channel. */
    private final SeekableByteChannel fc;

    /** Whether this editor owns {@link #fc} and must close it. */
    private final boolean ownsChannel;

    /** Offset of the archive header within the file. */
    private long headerOffset;

    /** Size of the archive header in bytes. */
    private int headerSize;

    /** Archive size as recorded in the header, possibly clamped. */
    private long archiveSize;

    /** Raw {@code wFormatVersion}. */
    private int formatVersion;

    private int sectorSizeShift;

    /** Sector size in bytes. */
    private int discBlockSize;

    /** Hash table position relative to {@link #headerOffset}. */
    private long hashPos;

    /** Block table position relative to {@link #headerOffset}. */
    private long blockPos;

    /** Number of hash table buckets. */
    private int hashSize;

    /** Number of block table entries. */
    private int blockSize;

    private HashTable hashTable;
    private BlockTable blockTable;
    private Listfile listFile = new Listfile();

    /**
     * A file waiting to be written on the next rebuild.
     * <p>
     * Exactly one of {@code path} and {@code data} is set.
     *
     * @param displayName the name as the caller spelled it, preserved for the
     *                    rebuilt list file.
     * @param path        source file to read at rebuild time.
     * @param data        content, already copied out of the caller's array.
     */
    private record PendingFile(String displayName, Path path, byte[] data) {
        static PendingFile of(String name, Path path) {
            return new PendingFile(name, path, null);
        }

        static PendingFile of(String name, byte[] data) {
            // Copy on insert: the caller is free to mutate its array
            // afterwards, and the old implementation would then write the
            // mutated content.
            return new PendingFile(name, null, data.clone());
        }

        byte[] read() throws IOException {
            return data != null ? data : Files.readAllBytes(path);
        }
    }

    /**
     * Files to add or replace on the next rebuild, keyed on canonical name and
     * kept in insertion order.
     * <p>
     * This was an identity-keyed map before 2.0, so {@code deleteFile(name)}
     * only worked if the caller passed the very same {@code String} instance
     * used at insert time; otherwise the "deleted" file quietly reappeared.
     */
    private final SequencedMap<String, PendingFile> pendingFiles = new LinkedHashMap<>();

    /** Whether to preserve the bytes before the archive header on rebuild. */
    private boolean keepHeaderOffset = true;

    private int newHeaderSize;
    private long newArchiveSize;
    private int newFormatVersion;
    private int newSectorSizeShift;
    private int newDiscBlockSize;
    private long newHashPos;
    private long newBlockPos;
    private int newHashSize;
    private int newBlockSize;

    /**
     * Whether the caller asked for a writable archive.
     * <p>
     * Distinct from {@link #canWrite}, which is the <em>effective</em> mode and
     * gets downgraded when the archive has no usable list file. Without the
     * distinction, {@link #setExternalListfile(File)} could never help: it
     * refuses to run on a read-only editor, so the very archives it exists for
     * were the ones it turned away.
     */
    private final boolean writeRequested;

    /**
     * If write operations are supported on the archive.
     */
    private boolean canWrite;

    /** The rebuilt archive image, available after a successful rebuild. */
    private byte[] outputByteArray;

    /**
     * Opens the MPQ archive at the specified path.
     * <p>
     * The file must already exist. Unlike before 2.0, opening a writable
     * archive never creates the file: probing a path that did not exist used to
     * leave an empty file behind (issue #38). Use
     * {@link #createEmptyArchive(File)} to make a new archive explicitly.
     * <p>
     * Changes made through this editor only reach the file system when
     * {@link #close()} is called.
     *
     * @param mpqArchive  path to an MPQ archive file.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if the archive is missing, damaged or unsupported.
     */
    public JMpqEditor(Path mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        writeRequested = !Arrays.asList(openOptions).contains(MPQOpenOption.READ_ONLY);
        canWrite = writeRequested;
        legacyCompatibility = Arrays.asList(openOptions).contains(MPQOpenOption.FORCE_V0);
        log.debug("Opening {}", mpqArchive);

        if (!Files.isRegularFile(mpqArchive)) {
            throw new JMpqException("Not an MPQ archive file: " + mpqArchive.toAbsolutePath());
        }

        SeekableByteChannel channel = null;
        try {
            final OpenOption[] fcOptions = canWrite
                ? new OpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE}
                : new OpenOption[]{StandardOpenOption.READ};
            channel = FileChannel.open(mpqArchive, fcOptions);
            fc = channel;
            ownsChannel = true;

            readMpq();
        } catch (JMpqException e) {
            closeQuietly(channel);
            // Keep the diagnostic in the top-level message: a caller who only
            // prints getMessage() must still learn what was wrong with the
            // archive, not just which file it was.
            throw new JMpqException(mpqArchive.toAbsolutePath() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new JMpqException("Cannot open MPQ archive " + mpqArchive.toAbsolutePath(), e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * Opens an MPQ archive held in memory.
     * <p>
     * A writable in-memory archive does not write anything back to the caller's
     * array; retrieve the rebuilt image with {@link #getOutputByteArray()}
     * after closing. To hold that promise the array is copied when the archive
     * is opened for writing, because the rebuild writes the finished image
     * through the channel and the channel would otherwise write straight into
     * the caller's array whenever the new image is no larger than the old one.
     * A read-only open never writes, so it wraps the array as it is.
     *
     * @param mpqArchive  the archive bytes.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if the archive is damaged or unsupported.
     */
    public JMpqEditor(byte[] mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        writeRequested = !Arrays.asList(openOptions).contains(MPQOpenOption.READ_ONLY);
        canWrite = writeRequested;
        legacyCompatibility = Arrays.asList(openOptions).contains(MPQOpenOption.FORCE_V0);

        SeekableByteChannel channel = null;
        try {
            // See the constructor docs: only a writable archive needs the copy.
            channel = new SeekableInMemoryByteChannel(canWrite ? mpqArchive.clone() : mpqArchive);
            fc = channel;
            ownsChannel = true;
            readMpq();
        } catch (JMpqException e) {
            closeQuietly(channel);
            throw new JMpqException("In-memory MPQ archive: " + e.getMessage(), e);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new JMpqException("Cannot open in-memory MPQ archive", e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    /**
     * See {@link #JMpqEditor(Path, MPQOpenOption...)}.
     *
     * @param mpqArchive  an MPQ archive file.
     * @param openOptions options to use when opening the archive.
     * @throws IOException if the archive is missing, damaged or unsupported.
     */
    public JMpqEditor(File mpqArchive, MPQOpenOption... openOptions) throws IOException {
        this(mpqArchive.toPath(), openOptions);
    }

    /**
     * See {@link #JMpqEditor(Path, MPQOpenOption...)}.
     *
     * @param mpqArchive an MPQ archive file.
     * @throws IOException if the archive is missing, damaged or unsupported.
     * @deprecated pass the open options explicitly; this constructor silently
     *             implies {@link MPQOpenOption#FORCE_V0}.
     */
    @Deprecated
    public JMpqEditor(File mpqArchive) throws IOException {
        this(mpqArchive.toPath(), MPQOpenOption.FORCE_V0);
    }

    private static void closeQuietly(SeekableByteChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                // The original failure is what the caller needs to see.
                log.debug("Ignoring failure while closing a partially opened archive.", suppressed);
            }
        }
    }

    private void readMpq() throws IOException {
        headerOffset = searchHeader();
        readHeaderSize();
        readHeader();
        checkLegacyCompat();
        validateTables();
        readHashTable();
        readBlockTable();
        hashTable.setBlockTableSize(blockSize);
        readListFile();
        readAttributesFile();
    }

    /**
     * @return the bytes of a minimal, empty version 0 archive.
     * @throws IOException if the archive image cannot be assembled.
     */
    public static byte[] createEmptyArchive() throws IOException {
        final int hashEntries = 2;
        final int blockEntries = 1;
        final int hashTableOffset = HEADER_SIZES[0];
        final int blockTableOffset = hashTableOffset + hashEntries * 16;

        HashTable hashTable = new HashTable(hashEntries);
        hashTable.setFileBlockIndex("(listfile)", HashTable.DEFAULT_LOCALE, 0);

        ByteBuffer hashTableBuffer = ByteBuffer.allocate(hashEntries * 16).order(ByteOrder.LITTLE_ENDIAN);
        hashTable.writeToBuffer(hashTableBuffer);
        hashTableBuffer.flip();
        new MPQEncryption(KEY_HASH_TABLE, false).processSingle(hashTableBuffer);
        hashTableBuffer.flip();

        // The block table is encrypted too. Emitting it in the clear produced an
        // archive whose block table decoded to garbage: every reader saw a
        // (listfile) block with a nonsense position and multi-gigabyte size.
        // It went unnoticed because the failure was swallowed on open.
        ByteBuffer blockTableBuffer =
            ByteBuffer.allocate(blockEntries * BlockTable.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        new Block(blockTableOffset + blockEntries * BlockTable.ENTRY_SIZE, 0, 0, EXISTS)
            .writeToBuffer(blockTableBuffer);
        blockTableBuffer.flip();
        new MPQEncryption(KEY_BLOCK_TABLE, false).processSingle(blockTableBuffer);
        blockTableBuffer.flip();

        ByteBuffer archive = ByteBuffer
            .allocate(HEADER_SIZES[0] + hashEntries * 16 + blockEntries * BlockTable.ENTRY_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        archive.putInt(ARCHIVE_HEADER_MAGIC);
        archive.putInt(HEADER_SIZES[0]);
        archive.putInt(archive.capacity());
        archive.putShort((short) 0); // format version 0
        archive.putShort((short) 3); // sector size shift: 4 KiB sectors
        archive.putInt(hashTableOffset);
        archive.putInt(blockTableOffset);
        archive.putInt(hashEntries);
        archive.putInt(blockEntries);
        archive.put(hashTableBuffer);
        archive.put(blockTableBuffer);
        return archive.array();
    }

    /**
     * Writes a minimal, empty version 0 archive, creating parent directories.
     *
     * @param mpqArchive destination file.
     * @throws IOException if the file cannot be written.
     */
    public static void createEmptyArchive(File mpqArchive) throws IOException {
        File parent = mpqArchive.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(mpqArchive.toPath(), createEmptyArchive());
    }

    private void checkLegacyCompat() throws IOException {
        if (!legacyCompatibility) {
            return;
        }
        // limit end of archive by end of file
        archiveSize = Math.min(archiveSize, fc.size() - headerOffset);

        // limit block table size by end of archive; a header whose block table
        // position lies past the end of the archive yields a negative delta,
        // which used to become a negative allocation size.
        final long delta = archiveSize - blockPos;
        if (delta > 0) {
            blockSize = (int) Math.min(blockSize, delta / BlockTable.ENTRY_SIZE);
        } else {
            log.warn("Block table position {} lies past the archive end {}; treating the block table as empty.",
                blockPos, archiveSize);
            blockSize = 0;
        }
    }

    private void readAttributesFile() {
        if (!hasFile("(attributes)")) {
            return;
        }
        try {
            attributes = new AttributesFile(extractFileAsBytes("(attributes)"));
        } catch (IOException | RuntimeException e) {
            // An unreadable (attributes) file is not fatal: it holds only
            // optional metadata. Say so instead of swallowing it silently.
            log.warn("Cannot parse this archive's (attributes) file; continuing without it.", e);
        }
    }

    /**
     * For use when the MPQ is missing a (listfile).
     * Adds this custom listfile into the MPQ and uses it
     * for rebuilding purposes.
     * If this is not a full listfile, the end result will be missing files.
     *
     * @param externalListfilePath Path to a file containing listfile entries
     */
    public void setExternalListfile(File externalListfilePath) {
        // Gate on what the caller asked for, not on the effective mode: an
        // archive whose own list file is missing or unreadable has already been
        // downgraded to read-only, and that is exactly the case this method
        // exists to repair.
        if (!writeRequested) {
            log.warn("The mpq was opened as readonly, setting an external listfile will have no effect.");
            return;
        }
        if (!externalListfilePath.exists()) {
            log.warn("External MPQ File: {} does not exist and will not be used",
                externalListfilePath.getAbsolutePath());
            return;
        }
        try {
            listFile = new Listfile(Files.readAllBytes(externalListfilePath.toPath()));
            // Restore writability before checking completeness, so the entries
            // that do not resolve are pruned as they are for a built-in list
            // file.
            canWrite = true;
            checkListfileEntries();
            log.debug("Applied external listfile with {} entries; archive is writable.", listFile.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not apply external listfile: {}", externalListfilePath.getAbsolutePath(), e);
            canWrite = false;
        }
    }

    /**
     * Reads the internal {@code (listfile)} and applies it as this archive's
     * list file.
     * <p>
     * An archive without a list file cannot be rebuilt without losing the files
     * whose names are unknown, so it is downgraded to read-only. Supply the
     * names with {@link #setExternalListfile(File)} to make it writable again.
     */
    private void readListFile() {
        if (hasFile("(listfile)")) {
            try {
                listFile = new Listfile(extractFileAsBytes("(listfile)"));
                checkListfileEntries();
            } catch (IOException | RuntimeException e) {
                log.warn("Extracting the mpq's listfile failed. It cannot be rebuilt.", e);
                canWrite = false;
            }
        } else {
            log.warn("The mpq doesn't contain a listfile. It cannot be rebuilt.");
            canWrite = false;
        }
    }

    /**
     * Performs verification to see if we know all the blocks of this file.
     * Prints warnings if we don't know all blocks.
     *
     * @throws JMpqException If retrieving valid blocks fails
     */
    private void checkListfileEntries() throws JMpqException {
        int hiddenFiles = (hasFile("(attributes)") ? 2 : 1) + (hasFile("(signature)") ? 1 : 0);
        if (canWrite) {
            checkListfileCompleteness(hiddenFiles);
        }
    }

    /**
     * Checks listfile for completeness against block table
     *
     * @param hiddenFiles Num. hidden files
     * @throws JMpqException If retrieving valid blocks fails
     */
    private void checkListfileCompleteness(int hiddenFiles) throws JMpqException {
        if (listFile.size() <= blockTable.getAllValidBlocks().size() - hiddenFiles) {
            log.warn("mpq's listfile is incomplete. Blocks without listfile entry will be discarded");
        }
        for (String fileName : listFile.getFiles()) {
            if (!hasFile(fileName)) {
                log.warn("listfile entry does not exist in archive and will be discarded: {}", fileName);
            }
        }
        listFile.getFileMap().entrySet().removeIf(file -> !hasFile(file.getValue()));

        for (Collection<String> collision : listFile.findKeyCollisions()) {
            log.warn("These listfile entries share one MPQ file key and cannot coexist: {}", collision);
        }
    }

    private void readBlockTable() throws IOException {
        ByteBuffer blockBuffer =
            ByteBuffer.allocate(blockSize * BlockTable.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
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
        ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + 4);
        readFully(probe, fc);
        headerSize = probe.getInt(0);
        if (legacyCompatibility) {
            // Warcraft III ignores this field for version 0 archives, and map
            // protectors fill it with garbage.
            headerSize = HEADER_SIZES[0];
        } else if (headerSize < HEADER_SIZES[0] || headerSize > HEADER_SIZES[HEADER_SIZES.length - 1]) {
            throw new JMpqException("Bad header size " + headerSize + " at offset " + headerOffset
                + "; expected between " + HEADER_SIZES[0] + " and " + HEADER_SIZES[HEADER_SIZES.length - 1]
                + ". Retry with MPQOpenOption.FORCE_V0 for protected Warcraft III maps.");
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
        ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

        final long fileSize = fc.size();
        for (long filePos = 0; filePos + probe.capacity() < fileSize; filePos += HEADER_ALIGNMENT) {
            probe.rewind();
            fc.position(filePos);
            readFully(probe, fc);

            final int sample = probe.getInt(0);
            if (sample == ARCHIVE_HEADER_MAGIC) {
                if (legacyCompatibility && !isPlausibleV0Header(filePos, fileSize)) {
                    // A decoy header planted by a map protector. Keep scanning
                    // instead of committing to the first magic value found.
                    log.debug("Ignoring implausible MPQ header at {}", filePos);
                    continue;
                }
                return filePos;
            }

            if (sample == USER_DATA_HEADER_MAGIC && !legacyCompatibility) {
                // MPQ user data header redirecting to the real MPQ header.
                // Ignored in legacy compatibility mode, because Warcraft III
                // ignores it too.
                probe.rewind();
                fc.position(filePos + 8);
                readFully(probe, fc);

                final long redirected = filePos + (probe.getInt(0) & 0xFFFFFFFFL);
                // The old code mutated the loop variable and then re-aligned it
                // with 'filePos &= -0x200'. A redirect offset below 0x200 left
                // filePos unchanged, so the loop never advanced and the open
                // hung forever.
                if (redirected + probe.capacity() < fileSize) {
                    probe.rewind();
                    fc.position(redirected);
                    readFully(probe, fc);
                    if (probe.getInt(0) == ARCHIVE_HEADER_MAGIC) {
                        return redirected;
                    }
                }
                log.debug("User data header at {} does not point at an archive header; continuing scan.", filePos);
            }
        }

        throw new JMpqException("No MPQ archive in file.");
    }

    /**
     * Cheap plausibility check on a candidate version 0 header.
     * <p>
     * Mirrors StormLib's {@code ERROR_FAKE_MPQ_HEADER} test: a header whose
     * table positions fall outside the file cannot be the real one.
     */
    private boolean isPlausibleV0Header(long filePos, long fileSize) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZES[0]).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(filePos);
        try {
            readFully(header, fc);
        } catch (EOFException e) {
            return false;
        }

        final int sectorShift = header.getShort(14) & 0xFFFF;
        final long hashTablePos = header.getInt(16) & 0xFFFFFFFFL;
        final long blockTablePos = header.getInt(20) & 0xFFFFFFFFL;
        final int hashTableSize = header.getInt(24) & HashTable.BLOCK_INDEX_MASK;

        return hashTablePos > 0
            && blockTablePos > 0
            && hashTableSize > 0
            && (sectorShift & 0xFF) <= MAX_SECTOR_SIZE_SHIFT
            && filePos + hashTablePos < fileSize
            && filePos + blockTablePos < fileSize;
    }

    /**
     * Read the MPQ archive header from the header chunk.
     */
    private void readHeader() throws IOException {
        // The first eight bytes (magic and header size) are already consumed.
        final int bodySize = headerSize - 8;
        ByteBuffer buffer = ByteBuffer.allocate(bodySize).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + 8);
        readFully(buffer, fc);
        buffer.rewind();

        archiveSize = buffer.getInt() & 0xFFFFFFFFL;
        formatVersion = buffer.getShort() & 0xFFFF;
        if (legacyCompatibility) {
            // force version 0 interpretation
            formatVersion = 0;
        }

        // StormLib: "Only low byte of sector size is really used".
        sectorSizeShift = buffer.getShort() & 0xFF;
        if (sectorSizeShift > MAX_SECTOR_SIZE_SHIFT) {
            throw new JMpqException("Sector size shift " + sectorSizeShift + " is out of range.");
        }
        discBlockSize = 512 << sectorSizeShift;

        hashPos = buffer.getInt() & 0xFFFFFFFFL;
        blockPos = buffer.getInt() & 0xFFFFFFFFL;
        hashSize = buffer.getInt() & HashTable.BLOCK_INDEX_MASK;
        blockSize = buffer.getInt();

        // version 1 extension
        if (formatVersion >= 1 && buffer.remaining() >= 12) {
            // TODO add high block table support
            buffer.getLong();

            // high 16 bits of file pos
            hashPos |= (buffer.getShort() & 0xFFFFL) << 32;
            blockPos |= (buffer.getShort() & 0xFFFFL) << 32;
        }

        // version 2 extension
        if (formatVersion >= 2 && buffer.remaining() >= 24) {
            // 64 bit archive size
            archiveSize = buffer.getLong();

            // TODO add support for BET and HET tables
            buffer.getLong();
            buffer.getLong();
        }

        // version 3 adds compressed table sizes and MD5 digests, both of which
        // are read in Phase 2. Nothing here depends on them.
    }

    /**
     * Validates the header's table descriptions against the actual file, and
     * clamps what StormLib clamps.
     * <p>
     * Every one of these numbers comes from an untrusted file and used to flow
     * straight into an allocation or a channel position.
     */
    private void validateTables() throws IOException {
        final long fileSize = fc.size();

        if (hashSize <= 0) {
            throw new JMpqException("Archive declares " + hashSize + " hash table entries.");
        }
        if (hashSize > HASH_TABLE_SIZE_MAX) {
            throw new JMpqException("Archive declares " + hashSize + " hash table entries, more than the "
                + HASH_TABLE_SIZE_MAX + " StormLib accepts.");
        }
        if (blockSize < 0) {
            throw new JMpqException("Archive declares " + blockSize + " block table entries.");
        }

        final long hashTableEnd = headerOffset + hashPos + (long) hashSize * 16;
        if (headerOffset + hashPos < 0 || hashTableEnd > fileSize) {
            throw new JMpqException("Hash table at " + (headerOffset + hashPos) + " spanning " + hashSize
                + " entries runs past the end of the " + fileSize + " byte file.");
        }

        final long blockTableStart = headerOffset + blockPos;
        if (blockTableStart < 0 || blockTableStart > fileSize) {
            throw new JMpqException("Block table position " + blockTableStart
                + " lies outside the " + fileSize + " byte file.");
        }
        final long blockTableEnd = blockTableStart + (long) blockSize * BlockTable.ENTRY_SIZE;
        if (blockTableEnd > fileSize) {
            // StormLib does exactly this: real archives in the wild (the audit
            // cites EWIX_v8_7.w3x) declare a block table far larger than the
            // file, and rejecting them would be stricter than the game.
            final int clamped = (int) ((fileSize - blockTableStart) / BlockTable.ENTRY_SIZE);
            log.warn("Archive declares {} block table entries but only {} fit in the file; using {}.",
                blockSize, clamped, clamped);
            blockSize = clamped;
        }
    }

    /**
     * Write header.
     *
     * @param buffer the buffer, positioned after the archive magic.
     */
    private void writeHeader(ByteBuffer buffer) {
        buffer.putInt(newHeaderSize);
        putUnsignedInt(buffer, newArchiveSize, "Archive size");
        buffer.putShort((short) newFormatVersion);
        buffer.putShort((short) newSectorSizeShift);
        putUnsignedInt(buffer, newHashPos, "Hash table position");
        putUnsignedInt(buffer, newBlockPos, "Block table position");
        buffer.putInt(newHashSize);
        buffer.putInt(newBlockSize);

        if (newFormatVersion >= 1) {
            // Hi-block table position (unused) and the hi-words of the hash and
            // block table positions.
            buffer.putLong(0);
            buffer.putShort((short) (newHashPos >>> 32));
            buffer.putShort((short) (newBlockPos >>> 32));
        }
    }

    private static void putUnsignedInt(ByteBuffer buffer, long value, String fieldName) {
        if (value < 0 || value > V0_MAX_ARCHIVE_SIZE) {
            throw new IllegalArgumentException(fieldName + " exceeds unsigned 32-bit range: " + value);
        }
        buffer.putInt((int) value);
    }

    /**
     * Sizes the rebuilt hash and block tables.
     * <p>
     * The hash table is twice the next power of two above the file count, which
     * keeps its load factor at or below 50% so lookups stay short.
     */
    private void calcNewTableSize(int fileCount) throws JMpqException {
        int current = 2;
        final int target = fileCount + 2;
        while (current < target) {
            current *= 2;
        }
        final long hashCapacity = (long) current * 2;
        if (hashCapacity > HASH_TABLE_SIZE_MAX) {
            throw new JMpqException("Cannot fit " + fileCount + " files: the hash table would need "
                + hashCapacity + " buckets, above the " + HASH_TABLE_SIZE_MAX + " maximum.");
        }
        newHashSize = (int) hashCapacity;
        newBlockSize = fileCount + 2;
    }

    /**
     * Extracts every file this archive can name into {@code dest}.
     *
     * @param dest destination directory.
     * @throws JMpqException if the destination is unusable or extraction fails.
     */
    public void extractAllFiles(File dest) throws JMpqException {
        if (!dest.isDirectory()) {
            throw new JMpqException("Destination location isn't a directory: " + dest);
        }
        final Path root = dest.toPath().toAbsolutePath().normalize();

        if (hasFile("(listfile)")) {
            final List<String> names = new ArrayList<>(listFile.getFiles());
            names.add("(listfile)");
            if (hasFile("(attributes)")) {
                names.add("(attributes)");
            }
            for (String name : names) {
                if (!hasFile(name)) {
                    continue;
                }
                log.debug("extracting: {}", name);
                try {
                    // Resolved inside the guard: an entry that would escape the
                    // destination is refused, and refusing it must cost the
                    // caller only that entry. Archives carrying a traversal
                    // name are exactly the ones where the rest still matters.
                    final Path target = resolveExtractionTarget(root, name);
                    Files.createDirectories(target.getParent());
                    getMpqFile(name).extractToPath(target);
                } catch (IOException | RuntimeException e) {
                    // Extracting everything is best effort by definition: one
                    // damaged file must not cost the caller the rest of the
                    // archive. RuntimeException is included because the codecs
                    // are C ports that signal bad data unchecked.
                    log.warn("File possibly corrupted and could not be extracted: {}", name, e);
                }
            }
            return;
        }

        // No list file: fall back to dumping blocks by index.
        try {
            int i = 0;
            for (Block b : blockTable.getAllValidBlocks()) {
                if (b.hasFlag(ENCRYPTED)) {
                    // Without a name there is no key, so the content is not
                    // recoverable.
                    continue;
                }
                readBlock(b, "").extractToPath(root.resolve(Integer.toString(i)));
                i++;
            }
        } catch (IOException e) {
            throw new JMpqException("Cannot extract this archive's blocks.", e);
        }
    }

    /**
     * Maps an archive-internal path to a destination path, refusing anything
     * that would escape the destination directory.
     * <p>
     * Archive contents are untrusted: an entry such as {@code ..\..\evil} would
     * otherwise write outside the directory the caller nominated.
     */
    private Path resolveExtractionTarget(Path root, String name) throws JMpqException {
        final String relative = name.replace('\\', '/');
        final Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new JMpqException("Refusing to extract <" + name + ">: it escapes the destination directory.");
        }
        return target;
    }

    /**
     * @return the number of live entries in the block table.
     * @throws JMpqException if the block table cannot be read.
     */
    public int getTotalFileCount() throws JMpqException {
        return blockTable.getAllValidBlocks().size();
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
            getMpqFile(name).extractToFile(dest);
        } catch (IOException e) {
            throw new JMpqException("Cannot extract <" + name + "> to " + dest, e);
        }
    }

    /**
     * Extracts the specified file out of the mpq.
     *
     * @param name name of the file
     * @return the file's content
     * @throws JMpqException if file is not found or access errors occur
     */
    public byte[] extractFileAsBytes(String name) throws JMpqException {
        try {
            return getMpqFile(name).extractToBytes();
        } catch (IOException e) {
            throw new JMpqException("Cannot extract <" + name + ">", e);
        }
    }

    /**
     * @param name name of the file
     * @return the file's content decoded as UTF-8
     * @throws JMpqException if file is not found or access errors occur
     */
    public String extractFileAsString(String name) throws JMpqException {
        return new String(extractFileAsBytes(name), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * @param name the file path
     * @return true if this archive holds the named file
     */
    public boolean hasFile(String name) {
        // The hash table can answer this without throwing; the old
        // implementation called getBlockIndexOfFile and caught the exception.
        return hashTable != null && hashTable.hasFile(name);
    }

    /**
     * @param name   the file path
     * @param locale preferred locale
     * @return true if this archive holds the named file in any locale
     */
    public boolean hasFile(String name, short locale) {
        return hashTable != null && hashTable.hasFile(name, locale);
    }

    /**
     * @return the names this archive's list file knows about.
     */
    public List<String> getFileNames() {
        return new ArrayList<>(listFile.getFiles());
    }

    /**
     * Extracts the specified file out of the mpq and writes it to the target
     * outputstream.
     * <p>
     * The stream is flushed but not closed; it belongs to the caller.
     *
     * @param name name of the file
     * @param dest the outputstream where the file's content is written
     * @throws JMpqException if file is not found or access errors occur
     */
    public void extractFile(String name, OutputStream dest) throws JMpqException {
        try {
            getMpqFile(name).extractToOutputStream(dest);
        } catch (IOException e) {
            throw new JMpqException("Cannot extract <" + name + ">", e);
        }
    }

    /**
     * @param name the file path
     * @return a handle on the named file's raw data
     * @throws IOException if the file is not present or cannot be read
     */
    public MpqFile getMpqFile(String name) throws IOException {
        return getMpqFile(name, HashTable.DEFAULT_LOCALE);
    }

    /**
     * @param name   the file path
     * @param locale preferred locale
     * @return a handle on the named file's raw data
     * @throws IOException if the file is not present or cannot be read
     */
    public MpqFile getMpqFile(String name, short locale) throws IOException {
        final int pos = hashTable.getFileBlockIndex(name, locale);
        return readBlock(blockTable.getBlockAtPos(pos), name);
    }

    /**
     * @param block a block
     * @return a handle on that block's raw data
     * @throws IOException if the block cannot be read
     */
    public MpqFile getMpqFileByBlock(BlockTable.Block block) throws IOException {
        if (block.hasFlag(ENCRYPTED)) {
            throw new JMpqException("Cannot access an encrypted block without knowing its file name.");
        }
        return readBlock(block, "");
    }

    private MpqFile readBlock(Block block, String name) throws IOException {
        final int compressedSize = block.getCompressedSize();
        if (compressedSize < 0) {
            throw new JMpqException("Block for <" + name + "> declares a negative size " + compressedSize + ".");
        }
        final long start = headerOffset + block.getFilePosition();
        if (start < 0 || start + compressedSize > fc.size()) {
            throw new JMpqException("Block for <" + name + "> spans [" + start + ", " + (start + compressedSize)
                + "), which is outside the " + fc.size() + " byte file.");
        }

        ByteBuffer buffer = ByteBuffer.allocate(compressedSize).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(start);
        readFully(buffer, fc);
        buffer.rewind();

        return new MpqFile(buffer, block, discBlockSize, name, formatVersion);
    }

    /**
     * @return handles on every readable block, skipping those that cannot be
     *         decoded without a name.
     * @throws IOException if the block table cannot be read
     */
    public List<MpqFile> getMpqFilesByBlockTable() throws IOException {
        List<MpqFile> mpqFiles = new ArrayList<>();
        for (Block block : blockTable.getAllValidBlocks()) {
            try {
                mpqFiles.add(getMpqFileByBlock(block));
            } catch (IOException e) {
                log.debug("Skipping unreadable block {}", block, e);
            }
        }
        return mpqFiles;
    }

    /**
     * Deletes the specified file from the mpq once you rebuild the mpq.
     *
     * @param name of the file inside the mpq
     */
    public void deleteFile(String name) {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }
        listFile.removeFile(name);
        pendingFiles.remove(MpqNames.canonical(name));
    }

    /**
     * Inserts the specified byte array into the mpq once you close the editor.
     * <p>
     * The array is copied, so the caller may reuse or modify it afterwards.
     *
     * @param name     of the file inside the mpq
     * @param input    the input byte array
     * @param override whether to override an existing file with the same name
     * @throws IllegalArgumentException when the archive already has the file
     *                                  and {@code override} is false
     */
    public void insertByteArray(String name, byte[] input, boolean override) {
        requireInsertable(name, override);
        listFile.addFile(name);
        pendingFiles.put(MpqNames.canonical(name), PendingFile.of(name, input));
    }

    /**
     * Inserts the specified byte array into the mpq once you close the editor.
     *
     * @param name  of the file inside the mpq
     * @param input the input byte array
     * @throws IllegalArgumentException when the archive already has the file
     */
    public void insertByteArray(String name, byte[] input) throws NonWritableChannelException, IllegalArgumentException {
        insertByteArray(name, input, false);
    }

    /**
     * Inserts the specified file into the mpq once you close the editor.
     * <p>
     * The file is read at rebuild time, so it must still exist and hold the
     * intended content when {@link #close()} runs.
     *
     * @param name of the file inside the mpq
     * @param file the file
     * @throws IOException              if the file cannot be used
     * @throws IllegalArgumentException when the archive already has the file
     */
    public void insertFile(String name, File file) throws IOException, IllegalArgumentException {
        insertFile(name, file, false);
    }

    /**
     * Inserts the specified file into the mpq once you close the editor.
     *
     * @param name     of the file inside the mpq
     * @param file     the file
     * @param override whether to override an existing file with the same name
     * @throws IOException if the file cannot be used
     */
    public void insertFile(String name, File file, boolean override) throws IOException {
        requireInsertable(name, override);
        log.debug("insert file: {}", name);
        listFile.addFile(name);
        pendingFiles.put(MpqNames.canonical(name), PendingFile.of(name, file.toPath()));
    }

    private void requireInsertable(String name, boolean override) {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }
        if (!override && listFile.containsFile(name)) {
            throw new IllegalArgumentException("Archive already contains file with name: " + name);
        }
    }

    /**
     * Closes the archive without rebuilding it.
     *
     * @throws IOException if the channel cannot be closed
     * @deprecated call {@link #close()}; it does not rebuild a read-only
     *             archive either.
     */
    @Deprecated
    public void closeReadOnly() throws IOException {
        if (ownsChannel) {
            fc.close();
        }
    }

    @Override
    public void close() throws IOException {
        close(true, true, false);
    }

    /**
     * @param buildListfile   whether to add a {@code (listfile)} to this mpq
     * @param buildAttributes whether to add an {@code (attributes)} file
     * @param recompress      whether to recompress existing files
     * @throws IOException if the rebuild fails
     */
    public void close(boolean buildListfile, boolean buildAttributes, boolean recompress) throws IOException {
        close(buildListfile, buildAttributes, new RecompressOptions(recompress));
    }

    /**
     * Rebuilds the archive, if it is writable, and releases it.
     * <p>
     * The rebuild is assembled in memory and then written over the archive in a
     * single pass. Nothing is staged on disk.
     *
     * @param buildListfile   whether to add a {@code (listfile)} to this mpq
     * @param buildAttributes whether to add an {@code (attributes)} file. Not
     *                        yet implemented; requesting it logs a warning
     *                        rather than silently doing nothing.
     * @param options         recompression settings
     * @throws IOException if the rebuild fails
     */
    public void close(boolean buildListfile, boolean buildAttributes, RecompressOptions options) throws IOException {
        if (!canWrite || !fc.isOpen()) {
            if (ownsChannel) {
                fc.close();
            }
            log.debug("Closed archive without rebuilding.");
            return;
        }

        try {
            rebuild(buildListfile, buildAttributes, options);
        } finally {
            if (fc.isOpen() && ownsChannel) {
                fc.close();
            }
        }
    }

    private void rebuild(boolean buildListfile, boolean buildAttributes, RecompressOptions options) throws IOException {
        final long startedAt = System.nanoTime();
        log.debug("Building mpq");

        if (buildAttributes && attributes == null) {
            log.warn("(attributes) generation is not implemented yet; the rebuilt archive will not have one.");
        } else if (attributes != null) {
            log.warn("This archive has an (attributes) file, which the rebuild does not preserve yet.");
        }

        final long base = keepHeaderOffset ? headerOffset : 0;
        newFormatVersion = formatVersion;
        newHeaderSize = HEADER_SIZES[Math.min(newFormatVersion, HEADER_SIZES.length - 1)];
        newSectorSizeShift = options.recompress
            ? Math.min(options.newSectorSizeShift, MAX_SECTOR_SIZE_SHIFT)
            : sectorSizeShift;
        newDiscBlockSize = options.recompress ? 512 << newSectorSizeShift : discBlockSize;

        final GrowingBuffer out = new GrowingBuffer(estimateImageSize());

        // Preserve whatever sits in front of the archive, if asked to.
        if (keepHeaderOffset && headerOffset > 0) {
            final ByteBuffer prefix = ByteBuffer.allocate((int) headerOffset).order(ByteOrder.LITTLE_ENDIAN);
            fc.position(0);
            readFully(prefix, fc);
            prefix.rewind();
            out.put(prefix);
        }

        // Reserve the header; it is filled in once the table positions are
        // known. The old code sized this region from the *old* header size,
        // which corrupted the archive whenever the version changed.
        out.putInt(ARCHIVE_HEADER_MAGIC);
        out.skip(newHeaderSize - 4);

        final List<Block> newBlocks = new ArrayList<>();
        final List<String> newFiles = new ArrayList<>();
        final List<String> existingFiles = sortedExistingFiles();
        long currentPos = base + newHeaderSize;

        // Files with a pending replacement are written from the pending data,
        // not copied from the archive.
        existingFiles.removeIf(name -> pendingFiles.containsKey(MpqNames.canonical(name)));

        currentPos = copyExistingFiles(out, existingFiles, newFiles, newBlocks, currentPos, base, options);
        currentPos = writePendingFiles(out, newFiles, newBlocks, currentPos, base, options);

        // Written even when empty. Skipping it for an archive with no known
        // names dropped the (listfile) altogether, and an archive without one
        // is downgraded to read-only the next time it is opened, so a single
        // rebuild used to make such an archive permanently unrebuildable.
        if (buildListfile) {
            currentPos = writeListfile(out, newFiles, newBlocks, currentPos, base, options);
        }

        calcNewTableSize(newFiles.size());
        newBlockSize = Math.max(newBlockSize, newBlocks.size());

        newHashPos = currentPos - base;
        newBlockPos = newHashPos + (long) newHashSize * 16;

        writeHashTable(out, newFiles);
        writeBlockTable(out, newBlocks);
        currentPos += (long) newHashSize * 16 + (long) newBlockSize * BlockTable.ENTRY_SIZE;

        // The archive spans from its header to the end of the block table.
        // The old code added one spurious byte here.
        newArchiveSize = currentPos - base;
        if (newFormatVersion == 0 && newArchiveSize > V0_MAX_ARCHIVE_SIZE) {
            throw new JMpqException("Rebuilt version 0 archive is " + newArchiveSize
                + " bytes, beyond the unsigned 32-bit header field.");
        }

        final ByteBuffer header = ByteBuffer.allocate(newHeaderSize - 4).order(ByteOrder.LITTLE_ENDIAN);
        writeHeader(header);
        out.putAt((int) base + 4, Arrays.copyOf(header.array(), header.position()));

        outputByteArray = out.toByteArray();

        fc.position(0);
        out.writeTo(fc);
        fc.truncate(fc.position());

        log.debug("Rebuild complete: {} bytes in {} ms.", outputByteArray.length,
            (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * Rough starting size for the rebuild buffer, to avoid a long chain of
     * doublings. Being wrong is harmless; the buffer grows.
     */
    private int estimateImageSize() {
        long estimate = Math.max(archiveSize, 0) + (keepHeaderOffset ? headerOffset : 0);
        for (PendingFile pending : pendingFiles.values()) {
            estimate += pending.data != null ? pending.data.length : 0;
        }
        return (int) Math.min(estimate + 4096, Integer.MAX_VALUE - 8);
    }

    /**
     * Existing file names in block table order.
     * <p>
     * Preserving the source order keeps rebuilt archives close to their input,
     * which makes diffs meaningful. Names whose block cannot be resolved sort
     * last.
     */
    private List<String> sortedExistingFiles() {
        final Map<String, Integer> order = new LinkedHashMap<>();
        for (String name : listFile.getFiles()) {
            order.put(name, blockIndexOrMax(name));
        }
        final List<String> sorted = new ArrayList<>(order.keySet());
        sorted.sort(java.util.Comparator.comparingInt(order::get));
        return sorted;
    }

    /**
     * @return the file's block index, or {@link Integer#MAX_VALUE} if it has
     *         none. Uses a lookup rather than catching an exception, which is
     *         what the old comparator did on every comparison.
     */
    private int blockIndexOrMax(String name) {
        if (!hashTable.hasFile(name)) {
            return Integer.MAX_VALUE;
        }
        try {
            return hashTable.getBlockIndexOfFile(name);
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
    }

    private long copyExistingFiles(GrowingBuffer out, List<String> existingFiles, List<String> newFiles,
                                   List<Block> newBlocks, long currentPos, long base, RecompressOptions options)
        throws IOException {
        // A file can only be copied with its stored bytes intact if the target
        // archive keeps the same sector geometry: a sector offset table is
        // expressed in the archive's sector size, and an archive has exactly one
        // of those. Recompressing into a different sector size therefore has to
        // re-encode everything, including the .wav files that are otherwise left
        // alone. Copying them regardless is what corrupted them before: the
        // rebuilt header advertised the new sector size while their offset
        // tables still described the old one, so they could no longer be read.
        final boolean canCopyVerbatim = newDiscBlockSize == discBlockSize;

        for (String existingName : existingFiles) {
            final boolean skipRecompression =
                canCopyVerbatim && existingName.toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
            if (options.recompress && !skipRecompression) {
                // Recompressing means decoding and re-encoding, so route the
                // file through the pending-file path instead of copying it.
                pendingFiles.put(MpqNames.canonical(existingName),
                    PendingFile.of(existingName, extractFileAsBytes(existingName)));
                continue;
            }

            final MpqFile file = getMpqFile(existingName);
            final Block newBlock = new Block(currentPos - base, 0, 0, file.getFlags());
            newBlocks.add(newBlock);
            newFiles.add(existingName);

            // Sized exactly: the file's stored bytes are copied through
            // verbatim apart from decryption, which preserves length.
            final ByteBuffer target = out.reserve(file.getCompressedSize());
            file.writeFileAndBlock(newBlock, target);
            out.advance(newBlock.getCompressedSize());
            currentPos += newBlock.getCompressedSize();
        }
        log.debug("Copied {} existing files.", newFiles.size());
        return currentPos;
    }

    private long writePendingFiles(GrowingBuffer out, List<String> newFiles, List<Block> newBlocks,
                                   long currentPos, long base, RecompressOptions options) throws IOException {
        for (PendingFile pending : pendingFiles.values()) {
            final byte[] fileData = pending.read();
            newFiles.add(pending.displayName());

            final Block newBlock = new Block(currentPos - base, 0, 0, 0);
            newBlocks.add(newBlock);
            writeEncodedFile(out, fileData, newBlock, "", options);
            currentPos += newBlock.getCompressedSize();
            log.debug("Added file {}", pending.displayName());
        }
        return currentPos;
    }

    private long writeListfile(GrowingBuffer out, List<String> newFiles, List<Block> newBlocks,
                               long currentPos, long base, RecompressOptions options) throws IOException {
        newFiles.add("(listfile)");
        final byte[] listfileArr = listFile.asByteArray();
        final Block newBlock = new Block(currentPos - base, 0, 0,
            EXISTS | COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
        newBlocks.add(newBlock);
        writeEncodedFile(out, listfileArr, newBlock, "(listfile)", options);
        log.debug("Added listfile ({} entries)", listFile.size());
        return currentPos + newBlock.getCompressedSize();
    }

    /**
     * Encodes one file into the image.
     * <p>
     * The encoder needs a bounded region to work in, and the exact compressed
     * size is only known afterwards, so a worst-case region is made addressable
     * and only the bytes actually produced are kept. Worst case is the sector
     * offset table plus every sector stored verbatim plus one type byte each;
     * nothing the encoder can produce exceeds that, because a sector that
     * compresses to no less than its raw size is stored raw. The old code
     * guessed {@code length * 2} and mapped that much file, which overflowed for
     * incompressible input.
     */
    private void writeEncodedFile(GrowingBuffer out, byte[] fileData, Block block, String name,
                                  RecompressOptions options) {
        final int sectors = Math.max(1, MpqFile.sectorCount(fileData.length, newDiscBlockSize));
        final int worstCase = (sectors + 1) * 4 + fileData.length + sectors;

        final ByteBuffer region = out.reserve(worstCase);
        MpqFile.writeFileAndBlock(fileData, block, region, newDiscBlockSize, name, options);
        out.advance(block.getCompressedSize());
    }

    private void writeHashTable(GrowingBuffer out, List<String> newFiles) throws IOException {
        final HashTable rebuilt = new HashTable(newHashSize);
        int blockIndex = 0;
        for (String file : newFiles) {
            rebuilt.setFileBlockIndex(file, HashTable.DEFAULT_LOCALE, blockIndex++);
        }

        final ByteBuffer buffer = ByteBuffer.allocate(newHashSize * 16);
        rebuilt.writeToBuffer(buffer);
        buffer.flip();
        new MPQEncryption(KEY_HASH_TABLE, false).processSingle(buffer);
        buffer.flip();
        out.put(buffer);
    }

    private void writeBlockTable(GrowingBuffer out, List<Block> newBlocks) {
        final ByteBuffer buffer =
            ByteBuffer.allocate(newBlockSize * BlockTable.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        BlockTable.writeNewBlocktable(newBlocks, newBlockSize, buffer);
        buffer.flip();
        out.put(buffer);
    }

    /**
     * The rebuilt archive image from the most recent {@link #close()}.
     * <p>
     * This is the only way to retrieve the result for an archive opened from a
     * byte array, since there is no file to write back to.
     *
     * @return the rebuilt image, or {@code null} if no rebuild has happened.
     */
    public byte[] getOutputByteArray() {
        return outputByteArray;
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
            if (src.read(buffer) < 1) {
                throw new EOFException("Cannot read enough bytes.");
            }
        }
    }

    /**
     * @return Whether the archive can be modified.
     */
    public boolean isCanWrite() {
        return canWrite;
    }

    /**
     * Whether to keep the data before the actual mpq in the file.
     *
     * @param keepHeaderOffset true to preserve the prefix, false to drop it so
     *                         the archive starts at offset 0.
     */
    public void setKeepHeaderOffset(boolean keepHeaderOffset) {
        this.keepHeaderOffset = keepHeaderOffset;
    }

    /**
     * @return this archive's raw {@code wFormatVersion}.
     */
    public int getFormatVersion() {
        return formatVersion;
    }

    /**
     * @return this archive's sector size in bytes.
     */
    public int getSectorSize() {
        return discBlockSize;
    }

    /**
     * @return the block table.
     */
    public BlockTable getBlockTable() {
        return blockTable;
    }

    /**
     * @return the hash table.
     */
    public HashTable getHashTable() {
        return hashTable;
    }

    @Override
    public String toString() {
        return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize
            + ", formatVersion=" + formatVersion + ", discBlockSize=" + discBlockSize
            + ", hashPos=" + hashPos + ", blockPos=" + blockPos + ", hashSize=" + hashSize
            + ", blockSize=" + blockSize + "]";
    }

    /**
     * @return an unmodifiable view of all list file entries.
     */
    public Collection<String> getListfileEntries() {
        return Collections.unmodifiableCollection(listFile.getFiles());
    }
}
