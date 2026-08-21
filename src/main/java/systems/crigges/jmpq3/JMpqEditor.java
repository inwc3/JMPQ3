package systems.crigges.jmpq3;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqFileEntry;
import org.inwc3.jmpq.MpqHeader;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.NonWritableChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Compatibility facade over the {@code org.inwc3.jmpq} core.
 *
 * @deprecated use {@link MpqArchive} to read and {@link MpqArchiveWriter} to
 *             write. This class exists so code written against JMPQ3 1.x keeps
 *             compiling and behaving as it did; it will be removed in a future
 *             major release.
 *
 * <h2>What this class is now</h2>
 * Every method here delegates. The 1400 lines of archive logic that used to
 * live in this file are gone, because two implementations of the same format
 * inevitably drift: during the 2.0 work the same sector-decryption defect had
 * to be found and fixed twice, once in each copy. There is now one
 * implementation and this facade.
 *
 * <h2>Behaviour deliberately preserved</h2>
 * <ul>
 * <li><b>Rebuild on close.</b> A writable editor rewrites the archive when
 * {@link #close()} runs. The core requires an explicit save instead, which is
 * the safer design, but changing it here would silently stop existing callers
 * from persisting their edits.</li>
 * <li><b>Read-only downgrade.</b> An archive with no usable {@code (listfile)}
 * cannot be enumerated, so it becomes read-only rather than risking a rebuild
 * that drops the files it cannot name. {@link #setExternalListfile(File)}
 * recovers it.</li>
 * <li><b>Insert semantics.</b> {@code insertFile} stores a path and reads it at
 * close time; {@code insertByteArray} copies immediately.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread safe; confine one editor to one thread. Separate editors are
 * independent.
 */
@Deprecated
public class JMpqEditor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JMpqEditor.class);

    /** {@code 'MPQ'}, the archive header signature. */
    public static final int ARCHIVE_HEADER_MAGIC = MpqHeader.ARCHIVE_SIGNATURE;

    /** {@code 'MPQ'}, the user data header signature. */
    public static final int USER_DATA_HEADER_MAGIC = MpqHeader.USER_DATA_SIGNATURE;

    /** The archive being read. Replaced after a rebuild. */
    private MpqArchive archive;

    /** A file the caller inserted, resolved at close time. */
    private record Insert(String name, byte[] bytes, Path file) {
    }

    /** Insertions, keyed on canonical name, in insertion order. */
    private final java.util.SequencedMap<String, Insert> inserts = new java.util.LinkedHashMap<>();

    /**
     * Names supplied through {@link #setExternalListfile(File)}.
     * <p>
     * An archive with no list file cannot enumerate itself, so a rebuild has to
     * be told which of its files to carry over.
     */
    private final java.util.SequencedSet<String> externalNames = new java.util.LinkedHashSet<>();

    /** The file this editor was opened from, or null for an in-memory archive. */
    private final Path path;

    /** The bytes an in-memory archive was opened from. */
    private byte[] memoryImage;

    private final boolean forceV0;
    private final boolean writeRequested;
    private boolean canWrite;
    private boolean keepHeaderOffset = true;
    private boolean closed;

    /** Names the caller deleted, so a rebuild does not carry them over. */
    private final List<String> deleted = new ArrayList<>();

    /** The rebuilt image from the most recent close. */
    private byte[] outputByteArray;

    /**
     * Opens the MPQ archive at the specified path.
     *
     * @param mpqArchive  path to an MPQ archive file; must exist.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if the archive is missing, damaged or unsupported.
     */
    public JMpqEditor(Path mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        this.path = mpqArchive;
        this.forceV0 = has(openOptions, MPQOpenOption.FORCE_V0);
        this.writeRequested = !has(openOptions, MPQOpenOption.READ_ONLY);
        this.canWrite = writeRequested;
        try {
            this.archive = MpqArchive.open(mpqArchive, openOptions());
        } catch (JMpqException e) {
            throw e;
        } catch (IOException e) {
            throw new JMpqException("Cannot open MPQ archive " + mpqArchive.toAbsolutePath(), e);
        }
        downgradeIfNotEnumerable();
    }

    /**
     * Opens an MPQ archive held in memory.
     * <p>
     * Nothing is written back to the caller's array; retrieve the rebuilt image
     * with {@link #getOutputByteArray()} after closing.
     *
     * @param mpqArchive  the archive bytes.
     * @param openOptions options to use when opening the archive.
     * @throws JMpqException if the archive is damaged or unsupported.
     */
    public JMpqEditor(byte[] mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        this.path = null;
        this.memoryImage = mpqArchive;
        this.forceV0 = has(openOptions, MPQOpenOption.FORCE_V0);
        this.writeRequested = !has(openOptions, MPQOpenOption.READ_ONLY);
        this.canWrite = writeRequested;
        try {
            this.archive = MpqArchive.open(mpqArchive, openOptions());
        } catch (JMpqException e) {
            throw e;
        } catch (IOException e) {
            throw new JMpqException("Cannot open in-memory MPQ archive", e);
        }
        downgradeIfNotEnumerable();
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

    private static boolean has(MPQOpenOption[] options, MPQOpenOption wanted) {
        for (MPQOpenOption option : options) {
            if (option == wanted) {
                return true;
            }
        }
        return false;
    }

    private MpqOpenOptions openOptions() {
        return forceV0 ? MpqOpenOptions.warcraft3() : MpqOpenOptions.defaults();
    }

    /**
     * An archive that cannot enumerate itself cannot be rebuilt without losing
     * the files whose names are unknown, so it is downgraded to read-only.
     */
    private void downgradeIfNotEnumerable() {
        // Presence of the (listfile), not whether it named anything: a freshly
        // created archive has an empty one and is perfectly writable, and
        // treating that as unenumerable made it impossible to add the first
        // file to it.
        if (canWrite && !archive.contains("(listfile)")) {
            log.warn("The mpq doesn't contain a listfile. It cannot be rebuilt.");
            canWrite = false;
        }
    }

    /**
     * @return the bytes of a minimal, empty version 0 archive.
     * @throws IOException if the image cannot be assembled.
     */
    public static byte[] createEmptyArchive() throws IOException {
        return MpqArchiveWriter.create(MpqWriteOptions.defaults().withPrefix(false)).toByteArray();
    }

    /**
     * Writes a minimal, empty version 0 archive, creating parent directories.
     *
     * @param mpqArchive destination file.
     * @throws IOException if the file cannot be written.
     */
    public static void createEmptyArchive(File mpqArchive) throws IOException {
        final File parent = mpqArchive.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(mpqArchive.toPath(), createEmptyArchive());
    }

    /**
     * For use when the MPQ is missing a {@code (listfile)}. Applies an external
     * list of names so the archive can be enumerated and rebuilt.
     *
     * @param externalListfilePath file containing one name per line.
     */
    public void setExternalListfile(File externalListfilePath) {
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
            final Listfile supplied = new Listfile(Files.readAllBytes(externalListfilePath.toPath()));
            int resolved = 0;
            for (String name : supplied.getFiles()) {
                if (archive.contains(name)) {
                    externalNames.add(name);
                    resolved++;
                } else {
                    log.debug("External listfile names <{}>, not held by the archive.", name);
                }
            }
            // A list file that resolves nothing leaves the archive as it was,
            // rather than claiming it became writable.
            if (resolved > 0) {
                canWrite = true;
            }
            log.debug("Applied external listfile: {} of {} names resolved.", resolved, supplied.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not apply external listfile: {}", externalListfilePath.getAbsolutePath(), e);
        }
    }

    /**
     * Extracts every file this archive can name into {@code dest}.
     *
     * @param dest destination directory.
     * @throws JMpqException if the destination is unusable.
     */
    public void extractAllFiles(File dest) throws JMpqException {
        if (!dest.isDirectory()) {
            throw new JMpqException("Destination location isn't a directory: " + dest);
        }
        final Path root = dest.toPath().toAbsolutePath().normalize();
        final List<String> names = new ArrayList<>(archive.names());
        for (String internal : List.of("(listfile)", "(attributes)", "(signature)")) {
            if (archive.contains(internal) && !names.contains(internal)) {
                names.add(internal);
            }
        }

        for (String name : names) {
            try {
                // Archive contents are untrusted: an entry naming "..\evil"
                // must not write outside the directory the caller nominated.
                final Path target = root.resolve(name.replace('\\', '/')).normalize();
                if (!target.startsWith(root)) {
                    throw new JMpqException("Refusing to extract <" + name
                        + ">: it escapes the destination directory.");
                }
                Files.createDirectories(target.getParent());
                Files.write(target, archive.read(name));
            } catch (IOException | RuntimeException e) {
                // Best effort by definition: one damaged file must not cost the
                // caller the rest of the archive.
                log.warn("File possibly corrupted and could not be extracted: {}", name, e);
            }
        }

        if (names.isEmpty()) {
            extractUnnamedBlocks(root);
        }
    }

    /**
     * Fallback for an archive with no list file: dump each readable block
     * under its block index, since there is no name to give it.
     */
    private void extractUnnamedBlocks(Path root) {
        int index = 0;
        for (MpqFileEntry entry : archive.entries()) {
            if (entry.isEncrypted() && entry.name().isEmpty()) {
                // Without a name there is no key, so the content is not
                // recoverable.
                index++;
                continue;
            }
            try {
                Files.write(root.resolve(Integer.toString(index)), archive.read(entry));
            } catch (IOException | RuntimeException e) {
                log.warn("Block {} could not be extracted.", index, e);
            }
            index++;
        }
    }

    /**
     * @return the number of live block table entries.
     */
    public int getTotalFileCount() {
        return archive.blockCount();
    }

    /**
     * @param name name of the file
     * @param dest destination to which the file's content is written
     * @throws JMpqException if the file is not found or cannot be decoded
     */
    public void extractFile(String name, File dest) throws JMpqException {
        try {
            Files.write(dest.toPath(), archive.read(name));
        } catch (IOException e) {
            throw wrap("Cannot extract <" + name + "> to " + dest, e);
        }
    }

    /**
     * @param name name of the file
     * @return the file's content
     * @throws JMpqException if the file is not found or cannot be decoded
     */
    public byte[] extractFileAsBytes(String name) throws JMpqException {
        try {
            return archive.read(name);
        } catch (IOException e) {
            throw wrap("Cannot extract <" + name + ">", e);
        }
    }

    /**
     * @param name name of the file
     * @return the file's content decoded as UTF-8
     * @throws JMpqException if the file is not found or cannot be decoded
     */
    public String extractFileAsString(String name) throws JMpqException {
        return new String(extractFileAsBytes(name), StandardCharsets.UTF_8);
    }

    /**
     * Extracts a file to a stream. The stream is flushed but not closed.
     *
     * @param name name of the file
     * @param dest destination stream
     * @throws JMpqException if the file is not found or cannot be decoded
     */
    public void extractFile(String name, OutputStream dest) throws JMpqException {
        try {
            archive.readTo(name, dest);
        } catch (IOException e) {
            throw wrap("Cannot extract <" + name + ">", e);
        }
    }

    /**
     * @param name the file path
     * @return true if this archive holds the named file
     */
    public boolean hasFile(String name) {
        return archive.contains(name);
    }

    /**
     * @param name   the file path
     * @param locale preferred locale
     * @return true if this archive holds the named file
     */
    public boolean hasFile(String name, short locale) {
        return archive.contains(name, locale);
    }

    /**
     * @return the names this archive's list file knows about, plus anything
     *         inserted since opening.
     */
    public List<String> getFileNames() {
        final List<String> names = new ArrayList<>(archive.names());
        for (String name : externalNames) {
            if (names.stream().noneMatch(known -> sameName(known, name))) {
                names.add(name);
            }
        }
        for (Insert insert : inserts.values()) {
            if (names.stream().noneMatch(known -> sameName(known, insert.name()))) {
                names.add(insert.name());
            }
        }
        names.removeIf(name -> deleted.stream().anyMatch(gone -> sameName(gone, name)));
        return names;
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
        final MpqFileEntry entry = archive.entry(name, locale)
            .orElseThrow(() -> new JMpqException("File Not Found <" + name + ">."));
        return legacyFile(entry, name);
    }

    /**
     * @param block a block
     * @return a handle on that block's raw data
     * @throws IOException if the block cannot be read
     */
    public MpqFile getMpqFileByBlock(Block block) throws IOException {
        if (block.hasFlag(MpqFile.ENCRYPTED)) {
            throw new JMpqException("Cannot access an encrypted block without knowing its file name.");
        }
        final MpqFileEntry entry = new MpqFileEntry("", (short) 0, block.getFlags(),
            block.getFilePosition(), block.getCompressedSize(), block.getNormalSize(), 0);
        return legacyFile(entry, "");
    }

    /**
     * @return handles on every readable block, skipping those that cannot be
     *         decoded without a name.
     * @throws IOException if the block table cannot be read
     */
    public List<MpqFile> getMpqFilesByBlockTable() throws IOException {
        final List<MpqFile> files = new ArrayList<>();
        for (MpqFileEntry entry : archive.entries()) {
            if (entry.isEncrypted()) {
                continue;
            }
            try {
                files.add(legacyFile(entry, entry.name()));
            } catch (IOException e) {
                log.debug("Skipping unreadable block {}", entry.blockIndex(), e);
            }
        }
        return files;
    }

    private MpqFile legacyFile(MpqFileEntry entry, String name) throws IOException {
        final ByteBuffer raw = ByteBuffer.wrap(archive.rawBytes(entry)).order(ByteOrder.LITTLE_ENDIAN);
        final Block block = new Block(entry.filePosition(), entry.compressedSize(),
            entry.normalSize(), entry.flags());
        return new MpqFile(raw, block, archive.header().sectorSize(), name,
            archive.header().formatVersion());
    }

    /**
     * Deletes the specified file from the mpq once the editor is closed.
     *
     * @param name of the file inside the mpq
     */
    public void deleteFile(String name) {
        requireWritable();
        inserts.remove(MpqNames.canonical(name));
        externalNames.removeIf(known -> sameName(known, name));
        deleted.add(name);
    }

    /**
     * Inserts the specified byte array into the mpq once the editor is closed.
     * <p>
     * The array is copied, so the caller may reuse it.
     *
     * @param name     of the file inside the mpq
     * @param input    the input byte array
     * @param override whether to override an existing file with the same name
     */
    public void insertByteArray(String name, byte[] input, boolean override) {
        requireWritable();
        requireAbsent(name, override);
        // Copy on insert: the caller may reuse its array afterwards.
        inserts.put(MpqNames.canonical(name), new Insert(name, input.clone(), null));
        deleted.removeIf(gone -> sameName(gone, name));
    }

    /**
     * @param name  of the file inside the mpq
     * @param input the input byte array
     */
    public void insertByteArray(String name, byte[] input)
        throws NonWritableChannelException, IllegalArgumentException {
        insertByteArray(name, input, false);
    }

    /**
     * Inserts the specified file into the mpq once the editor is closed. The
     * file is read at close time, so it must still exist then.
     *
     * @param name of the file inside the mpq
     * @param file the file
     */
    public void insertFile(String name, File file) throws IOException, IllegalArgumentException {
        insertFile(name, file, false);
    }

    /**
     * @param name     of the file inside the mpq
     * @param file     the file
     * @param override whether to override an existing file with the same name
     */
    public void insertFile(String name, File file, boolean override) throws IOException {
        requireWritable();
        requireAbsent(name, override);
        log.debug("insert file: {}", name);
        // Stored as a path and read at close time, as 1.x did.
        inserts.put(MpqNames.canonical(name), new Insert(name, null, file.toPath()));
        deleted.removeIf(gone -> sameName(gone, name));
    }

    private void requireWritable() {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }
    }

    private void requireAbsent(String name, boolean override) {
        if (override || deleted.stream().anyMatch(gone -> sameName(gone, name))) {
            return;
        }
        if (inserts.containsKey(MpqNames.canonical(name)) || archive.contains(name)) {
            throw new IllegalArgumentException("Archive already contains file with name: " + name);
        }
    }

    private static boolean sameName(String a, String b) {
        return MpqNames.canonical(a).equals(MpqNames.canonical(b));
    }

    private MpqWriteOptions writeOptions(RecompressOptions recompress, boolean buildListfile) {
        MpqWriteOptions options = MpqWriteOptions.defaults()
            .withFormatVersion(Math.min(archive.header().formatVersion(), MpqWriteOptions.MAX_WRITABLE_VERSION))
            .withSectorSizeShift(recompress.recompress
                ? Math.min(recompress.newSectorSizeShift, MpqHeader.MAX_SECTOR_SIZE_SHIFT)
                : archive.header().sectorSizeShift())
            .withRecompression(recompress)
            .withListfile(buildListfile)
            .withPrefix(keepHeaderOffset);
        return options;
    }

    /**
     * Closes the archive without rebuilding it.
     *
     * @throws IOException if the archive cannot be released
     * @deprecated call {@link #close()}; it does not rebuild a read-only
     *             archive either.
     */
    @Deprecated
    public void closeReadOnly() throws IOException {
        archive.close();
        closed = true;
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
     *
     * @param buildListfile   whether to add a {@code (listfile)} to this mpq
     * @param buildAttributes whether to add an {@code (attributes)} file. Not
     *                        implemented; requesting it logs a warning rather
     *                        than silently doing nothing.
     * @param options         recompression settings
     * @throws IOException if the rebuild fails
     */
    public void close(boolean buildListfile, boolean buildAttributes, RecompressOptions options)
        throws IOException {
        if (closed) {
            return;
        }
        if (!canWrite) {
            archive.close();
            closed = true;
            log.debug("Closed archive without rebuilding.");
            return;
        }
        if (buildAttributes) {
            log.warn("(attributes) generation is not implemented; the rebuilt archive will not have one.");
        }

        try {
            // The image has to be built while the archive is still open, because
            // file content is read lazily, and written once it is closed,
            // because a mapped file cannot be replaced on Windows.
            outputByteArray = build(options, buildListfile);
        } finally {
            archive.close();
            closed = true;
        }

        if (path != null) {
            Files.write(path, outputByteArray);
        } else {
            memoryImage = outputByteArray;
        }
    }

    /**
     * Builds the rebuilt image: whatever the archive could name, plus anything
     * an external list file named, minus deletions, with insertions on top.
     */
    private byte[] build(RecompressOptions options, boolean buildListfile) throws IOException {
        final MpqArchiveWriter writer =
            MpqArchiveWriter.from(archive, writeOptions(options, buildListfile));

        // Files the archive holds but could not name itself, recovered from an
        // external list file.
        for (String name : externalNames) {
            if (!writer.contains(name) && archive.contains(name)) {
                writer.put(name, archive.read(name));
            }
        }
        for (String gone : deleted) {
            writer.remove(gone);
        }
        // Insertions last, so they win over whatever the archive held.
        for (Insert insert : inserts.values()) {
            if (insert.bytes() != null) {
                writer.put(insert.name(), insert.bytes());
            } else {
                writer.put(insert.name(), insert.file());
            }
        }
        return writer.toByteArray();
    }

    /**
     * The rebuilt archive image from the most recent {@link #close()}.
     *
     * @return the rebuilt image, or {@code null} if no rebuild has happened.
     */
    public byte[] getOutputByteArray() {
        return outputByteArray;
    }

    /**
     * @return whether the archive can be modified.
     */
    public boolean isCanWrite() {
        return canWrite;
    }

    /**
     * @param keepHeaderOffset true to preserve bytes before the archive header,
     *                         false to move the archive to offset 0.
     */
    public void setKeepHeaderOffset(boolean keepHeaderOffset) {
        this.keepHeaderOffset = keepHeaderOffset;
    }

    /**
     * @return this archive's raw {@code wFormatVersion}.
     */
    public int getFormatVersion() {
        return archive.header().formatVersion();
    }

    /**
     * @return this archive's sector size in bytes.
     */
    public int getSectorSize() {
        return archive.header().sectorSize();
    }

    /**
     * @return the block table.
     * @deprecated exposes the on-disk index; use {@link MpqArchive#entries()}.
     */
    @Deprecated
    public BlockTable getBlockTable() {
        final List<Block> rows = new ArrayList<>();
        for (MpqFileEntry entry : archive.rawBlocks()) {
            rows.add(new Block(entry.filePosition(), entry.compressedSize(),
                entry.normalSize(), entry.flags()));
        }
        return BlockTable.of(rows);
    }

    /**
     * @return the hash table.
     * @deprecated exposes the on-disk index; use {@link MpqArchive#entry(String)}.
     */
    @Deprecated
    public HashTable getHashTable() {
        return archive.hashTable();
    }

    /**
     * @return an unmodifiable view of all list file entries.
     */
    public Collection<String> getListfileEntries() {
        return Collections.unmodifiableCollection(getFileNames());
    }

    private static JMpqException wrap(String message, IOException cause) {
        return cause instanceof JMpqException already
            ? new JMpqException(message + ": " + already.getMessage(), already)
            : new JMpqException(message, cause);
    }

    @Override
    public String toString() {
        return "JMpqEditor[" + archive + ", canWrite=" + canWrite + "]";
    }

    /** @return the archive this facade delegates to. */
    Optional<MpqArchive> delegate() {
        return Optional.ofNullable(archive);
    }
}
