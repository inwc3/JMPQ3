package org.inwc3.jmpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.Listfile;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Read-only access to an MPQ archive.
 * <p>
 * This is the read half of the new core. It opens an archive, tells you what is
 * in it, and decodes files. It never writes: producing a modified archive is
 * {@code MpqArchiveWriter}'s job, which takes an explicit {@code save}. That
 * separation is the point — the old {@code JMpqEditor} rebuilt the archive as a
 * side effect of {@code close()}, so forgetting a flag or throwing midway could
 * rewrite a file the caller only meant to read.
 *
 * <h2>Locales</h2>
 * MPQ can hold several localised versions of one path. Lookups take an optional
 * locale and follow the format's own preference order: the requested locale
 * first, then the neutral default, then whatever is present.
 *
 * <h2>Thread safety</h2>
 * An archive opened from a {@link Path} is confined to the thread that opened
 * it, because its mapping is. Archives opened from a byte array are safe to
 * read concurrently. Either way nothing here mutates the archive.
 */
public final class MpqArchive implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MpqArchive.class);

    /** Encryption key for hash table data. */
    private static final int KEY_HASH_TABLE = tableKey("(hash table)");

    /** Encryption key for block table data. */
    private static final int KEY_BLOCK_TABLE = tableKey("(block table)");

    /**
     * Files an archive holds by convention rather than by being listed. A list
     * file does not name itself, so these have to be known rather than
     * discovered.
     */
    private static final List<String> INTERNAL_NAMES =
        List.of("(listfile)", "(attributes)", "(signature)");

    private static int tableKey(String name) {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process(name);
        return hasher.getHash();
    }

    private final MpqSource source;
    private final MpqHeader header;
    private final HashTable hashTable;
    private final MpqFileReader reader;
    private final short defaultLocale;

    /** Block table rows, indexed as the hash table addresses them. */
    private final MpqFileEntry[] blocks;

    /** Names from the archive's list file, canonical name to spelling. */
    private final SequencedMap<String, String> names = new LinkedHashMap<>();

    private MpqArchive(MpqSource source, MpqOpenOptions options) throws IOException {
        this.source = source;
        this.defaultLocale = options.defaultLocale();
        this.header = MpqHeader.parse(source, options.forceV0());
        if (header.hiBlockTablePosition() != 0) {
            // A hi-block table holds the upper 16 bits of each file position,
            // for archives whose data passes 4 GiB. Reading only the low word
            // would seek to the wrong place, so refuse rather than silently
            // misread. Supporting it belongs with the v2-v4 read work (P2-2).
            throw new JMpqException("This archive uses a hi-block table, for file positions"
                + " beyond 4 GiB, which is not supported yet.");
        }
        this.reader = new MpqFileReader(source, header);
        this.blocks = readBlockTable();
        this.hashTable = readHashTable();
        readNames();
    }

    /**
     * Opens an archive file, mapping it for reading.
     *
     * @param path    archive to open; must exist.
     * @param options how to interpret the archive.
     * @return the open archive.
     * @throws IOException if the archive is missing, damaged or unsupported.
     */
    public static MpqArchive open(Path path, MpqOpenOptions options) throws IOException {
        final MpqSource source = MpqSource.ofFile(path);
        return adopt(source, options, path.toString());
    }

    /**
     * Opens an archive file with the default options.
     *
     * @param path archive to open; must exist.
     * @return the open archive.
     * @throws IOException if the archive is missing, damaged or unsupported.
     */
    public static MpqArchive open(Path path) throws IOException {
        return open(path, MpqOpenOptions.defaults());
    }

    /**
     * Opens an archive held in memory. The array is not copied and is never
     * written.
     *
     * @param archive archive bytes.
     * @param options how to interpret the archive.
     * @return the open archive.
     * @throws IOException if the archive is damaged or unsupported.
     */
    public static MpqArchive open(byte[] archive, MpqOpenOptions options) throws IOException {
        return adopt(MpqSource.ofArray(archive), options, "byte[]");
    }

    /**
     * Opens an archive from a channel, reading it into memory.
     *
     * @param channel channel holding an archive.
     * @param options how to interpret the archive.
     * @return the open archive.
     * @throws IOException if the archive cannot be read.
     */
    public static MpqArchive open(SeekableByteChannel channel, MpqOpenOptions options) throws IOException {
        return adopt(MpqSource.ofChannel(channel), options, "channel");
    }

    private static MpqArchive adopt(MpqSource source, MpqOpenOptions options, String what) throws IOException {
        try {
            return new MpqArchive(source, options);
        } catch (JMpqException e) {
            source.close();
            // Keep the diagnostic in the top-level message: a caller printing
            // only getMessage() must still learn what was wrong.
            throw new JMpqException(what + ": " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            source.close();
            throw e;
        }
    }

    /**
     * @return the parsed archive header.
     */
    public MpqHeader header() {
        return header;
    }

    /**
     * @return the number of block table rows in use.
     */
    public int blockCount() {
        return (int) java.util.Arrays.stream(blocks).filter(MpqFileEntry::exists).count();
    }

    /**
     * @return the file names this archive can enumerate, in list file order.
     *         Archives without a usable list file return an empty list; their
     *         files are still readable by exact name.
     */
    public List<String> names() {
        return List.copyOf(names.sequencedValues());
    }

    /**
     * @param name file path.
     * @return whether the archive holds it, in any locale.
     */
    public boolean contains(String name) {
        return contains(name, defaultLocale);
    }

    /**
     * @param name   file path.
     * @param locale preferred locale.
     * @return whether the archive holds it.
     */
    public boolean contains(String name, short locale) {
        return hashTable.hasFile(name, locale);
    }

    /**
     * Looks up a file.
     *
     * @param name file path.
     * @return the entry, or empty if the archive does not hold it.
     */
    public Optional<MpqFileEntry> entry(String name) {
        return entry(name, defaultLocale);
    }

    /**
     * Looks up a file, preferring a locale.
     *
     * @param name   file path.
     * @param locale preferred locale; the format's preference order applies.
     * @return the entry, or empty if the archive does not hold it.
     */
    public Optional<MpqFileEntry> entry(String name, short locale) {
        if (!hashTable.hasFile(name, locale)) {
            return Optional.empty();
        }
        try {
            final int index = hashTable.getFileBlockIndex(name, locale);
            if (index < 0 || index >= blocks.length) {
                return Optional.empty();
            }
            final short storedLocale = hashTable.getFileLocale(name, locale);
            return Optional.of(new MpqFileEntry(name, storedLocale, blocks[index].flags(),
                blocks[index].filePosition(), blocks[index].compressedSize(),
                blocks[index].normalSize(), index));
        } catch (IOException e) {
            // hasFile already said yes, so this means the tables disagree.
            log.debug("Hash table names <{}> but its block is unusable.", name, e);
            return Optional.empty();
        }
    }

    /**
     * Every block in use, named where the list file could name it.
     * <p>
     * Useful for archives with no list file, where the only way to see the
     * contents is to walk the block table.
     *
     * @return one entry per live block, in block table order.
     */
    public List<MpqFileEntry> entries() {
        // Correlate every hash table mapping, not one lookup per name. A lookup
        // resolves a single variant by the format's preference order, so naming
        // blocks that way left every other localised variant of a path reported
        // with no name and locale 0 -- misstating its locale, and leaving an
        // encrypted variant unreadable because its key derives from the name.
        final Map<Long, String> namesByKey = new LinkedHashMap<>();
        for (String name : names.values()) {
            namesByKey.put(MpqNames.fileKey(name), name);
        }
        // The internal files are known by name even though a list file does not
        // list itself. Without these they would count as unnameable, which is
        // what unnamedBlockCount reports as data a rebuild would lose.
        for (String internal : INTERNAL_NAMES) {
            if (hashTable.hasFile(internal)) {
                namesByKey.put(MpqNames.fileKey(internal), internal);
            }
        }
        final Map<Integer, HashTable.Mapping> byBlock = new LinkedHashMap<>();
        for (HashTable.Mapping mapping : hashTable.mappings()) {
            // Prefer a mapping whose name is known, so a block reachable by
            // name is reported with it.
            final HashTable.Mapping existing = byBlock.get(mapping.blockIndex());
            if (existing == null || (!namesByKey.containsKey(existing.key())
                && namesByKey.containsKey(mapping.key()))) {
                byBlock.put(mapping.blockIndex(), mapping);
            }
        }

        final List<MpqFileEntry> result = new ArrayList<>(blocks.length);
        for (MpqFileEntry block : blocks) {
            if (!block.exists()) {
                continue;
            }
            final HashTable.Mapping mapping = byBlock.get(block.blockIndex());
            if (mapping == null) {
                result.add(block);
                continue;
            }
            final String name = namesByKey.getOrDefault(mapping.key(), "");
            result.add(new MpqFileEntry(name, mapping.locale(), block.flags(),
                block.filePosition(), block.compressedSize(), block.normalSize(),
                block.blockIndex()));
        }
        return result;
    }

    /**
     * The locales a path is stored under.
     * <p>
     * MPQ can hold several localised variants of one path, and a lookup returns
     * only one of them. This reports all of them, so a caller can decide which
     * to read and a rebuild can carry them all over.
     *
     * @param name file path.
     * @return the locales present, in bucket order; empty if the archive does
     *         not hold the path at all.
     */
    public List<Short> localesOf(String name) {
        final long key = MpqNames.fileKey(name);
        final List<Short> locales = new ArrayList<>(1);
        for (HashTable.Mapping mapping : hashTable.mappings()) {
            if (mapping.key() == key) {
                locales.add(mapping.locale());
            }
        }
        return locales;
    }

    /**
     * Decodes a file.
     *
     * @param name file path.
     * @return the file's content.
     * @throws IOException if the archive does not hold it, or the data is
     *                     damaged.
     */
    public byte[] read(String name) throws IOException {
        return read(name, defaultLocale);
    }

    /**
     * Decodes a file, preferring a locale.
     *
     * @param name   file path.
     * @param locale preferred locale.
     * @return the file's content.
     * @throws IOException if the archive does not hold it, or the data is
     *                     damaged.
     */
    public byte[] read(String name, short locale) throws IOException {
        return reader.read(require(name, locale));
    }

    /**
     * Decodes a file straight to a stream, a sector at a time.
     * <p>
     * The stream is flushed but never closed; it belongs to the caller.
     *
     * @param name   file path.
     * @param target destination.
     * @throws IOException if the archive does not hold it, or the data is
     *                     damaged.
     */
    public void readTo(String name, OutputStream target) throws IOException {
        reader.readTo(require(name, defaultLocale), target);
    }

    /**
     * Decodes a previously looked-up entry.
     *
     * @param entry entry from {@link #entry(String)} or {@link #entries()}.
     * @return the file's content.
     * @throws IOException if the data is damaged.
     */
    public byte[] read(MpqFileEntry entry) throws IOException {
        return reader.read(entry);
    }

    /**
     * Decodes a previously looked-up entry to a stream.
     *
     * @param entry  entry from {@link #entry(String)} or {@link #entries()}.
     * @param target destination; flushed but not closed.
     * @throws IOException if the data is damaged.
     */
    public void readTo(MpqFileEntry entry, OutputStream target) throws IOException {
        reader.readTo(entry, target);
    }

    private MpqFileEntry require(String name, short locale) throws IOException {
        return entry(name, locale).orElseThrow(
            () -> new JMpqException("No such file in " + source.origin() + ": <" + name + ">"));
    }

    private MpqFileEntry[] readBlockTable() throws IOException {
        final int count = header.blockTableEntries();
        // long arithmetic: a 2 GiB archive can describe enough block entries
        // that count * 16 overflows int, which would reach source.bytes as a
        // negative length and fail with a message about the wrong thing.
        final long tableBytes = (long) count * MpqHeader.BLOCK_ENTRY_SIZE;
        if (tableBytes > Integer.MAX_VALUE - 8) {
            throw new JMpqException("Block table of " + count + " entries needs "
                + tableBytes + " bytes, more than can be held in memory.");
        }
        final byte[] encrypted = source.bytes(header.blockTableFileOffset(), (int) tableBytes);

        final ByteBuffer plain = ByteBuffer.allocate(encrypted.length).order(ByteOrder.LITTLE_ENDIAN);
        new MPQEncryption(KEY_BLOCK_TABLE, true).processFinal(ByteBuffer.wrap(encrypted), plain);
        plain.rewind();

        final MpqFileEntry[] entries = new MpqFileEntry[count];
        for (int i = 0; i < count; i++) {
            final long filePosition = Integer.toUnsignedLong(plain.getInt());
            final int compressedSize = plain.getInt();
            final int normalSize = plain.getInt();
            final int flags = plain.getInt();
            entries[i] = new MpqFileEntry("", (short) 0, flags, filePosition,
                compressedSize, normalSize, i);
        }
        return entries;
    }

    private HashTable readHashTable() throws IOException {
        // Bounded by MpqHeader at MAX_HASH_TABLE_ENTRIES, so this cannot
        // overflow: 0x80000 * 16 is 8 MiB.
        final byte[] encrypted = source.bytes(header.hashTableFileOffset(),
            header.hashTableEntries() * MpqHeader.HASH_ENTRY_SIZE);

        final ByteBuffer plain = ByteBuffer.allocate(encrypted.length).order(ByteOrder.LITTLE_ENDIAN);
        new MPQEncryption(KEY_HASH_TABLE, true).processFinal(ByteBuffer.wrap(encrypted), plain);
        plain.rewind();

        final HashTable table = new HashTable(header.hashTableEntries());
        table.readFromBuffer(plain);
        // StormLib only accepts a hash entry whose block index addresses a real
        // block, which is what makes it skip the dangling entries protectors
        // plant.
        table.setBlockTableSize(blocks.length);
        return table;
    }

    /**
     * Populates the name list from the archive's {@code (listfile)}.
     * <p>
     * A missing or unreadable list file is not an error: the archive is simply
     * not enumerable, and files remain readable by exact name. Names that do
     * not resolve are dropped, because they name nothing.
     */
    private void readNames() {
        if (!contains("(listfile)")) {
            log.debug("{} has no (listfile); it cannot be enumerated.", source.origin());
            return;
        }
        final Listfile listfile;
        try {
            listfile = new Listfile(read("(listfile)"));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read the (listfile) of {}; the archive is not enumerable.",
                source.origin(), e);
            return;
        }

        for (String name : listfile.getFiles()) {
            if (contains(name)) {
                names.put(MpqNames.canonical(name), name);
            } else {
                log.debug("(listfile) of {} names <{}>, which the archive does not hold.",
                    source.origin(), name);
            }
        }
    }

    /**
     * Whether this archive can list its own contents.
     * <p>
     * An archive without a usable {@code (listfile)} cannot: the hash table
     * stores hashes, not names, so there is nothing to enumerate. Its files are
     * still readable by exact name. This is a queryable fact rather than a log
     * line, because a caller about to rebuild needs to know that names it
     * cannot see would be dropped.
     *
     * @return whether {@link #names()} reflects the whole archive.
     */
    public boolean isEnumerable() {
        return !names.isEmpty();
    }

    /**
     * How many live blocks no name resolves to.
     * <p>
     * A rebuild can only carry over files it can name, so this is exactly how
     * many files a rebuild would discard. Non-zero means the archive's list file
     * is incomplete, which protected archives do deliberately. Before 2.0 this
     * was a log warning at open time and nothing a caller could act on.
     *
     * @return the number of unnameable live blocks.
     */
    public int unnamedBlockCount() {
        int unnamed = 0;
        for (MpqFileEntry entry : entries()) {
            if (entry.name().isEmpty()) {
                unnamed++;
            }
        }
        return unnamed;
    }

    /**
     * The hash table backing this archive.
     * <p>
     * Exposed for the deprecated {@code JMpqEditor} adapter, whose public API
     * hands this object to callers. New code should use {@link #entry(String)}
     * and {@link #localesOf(String)} instead, which do not require knowing how
     * the format stores its index.
     *
     * @return the live hash table.
     */
    public HashTable hashTable() {
        return hashTable;
    }

    /**
     * A file's bytes exactly as stored, without decryption.
     * <p>
     * Exposed for the deprecated adapter, which constructs legacy
     * {@code MpqFile} objects that do their own decoding. New code should use
     * {@link #read(MpqFileEntry)}.
     *
     * @param entry the file.
     * @return exactly {@link MpqFileEntry#compressedSize()} bytes.
     * @throws IOException if the range lies outside the archive.
     */
    public byte[] rawBytes(MpqFileEntry entry) throws IOException {
        return source.bytes(header.headerOffset() + entry.filePosition(), entry.compressedSize());
    }

    /**
     * Every block table row, live or not, in table order.
     * <p>
     * Exposed for the deprecated adapter. {@link #entries()} is the supported
     * form: it skips dead rows and attaches names and locales.
     *
     * @return the raw rows.
     */
    public List<MpqFileEntry> rawBlocks() {
        return List.of(blocks);
    }

    /**
     * The bytes preceding the archive header.
     * <p>
     * Warcraft III maps carry a 512-byte prefix before the archive proper, and
     * a rebuild that means to stay loadable has to keep it.
     *
     * @return the prefix, empty when the archive starts at offset 0.
     * @throws IOException if the prefix cannot be read.
     */
    byte[] prefixBytes() throws IOException {
        final long length = header.headerOffset();
        if (length <= 0) {
            return new byte[0];
        }
        if (length > Integer.MAX_VALUE - 8) {
            throw new JMpqException("Archive is preceded by " + length
                + " bytes, too many to carry over.");
        }
        return source.bytes(0, (int) length);
    }

    /**
     * The stored bytes of a file with any encryption removed, for a verbatim
     * copy into another archive of the same sector size.
     *
     * @param entry the file to copy.
     * @return exactly {@link MpqFileEntry#compressedSize()} bytes.
     * @throws IOException if the data is damaged.
     */
    byte[] storedBytesDecrypted(MpqFileEntry entry) throws IOException {
        return reader.storedBytesDecrypted(entry);
    }

    /**
     * Releases the archive. For a file-backed archive the file is fully
     * released by the time this returns.
     */
    @Override
    public void close() {
        source.close();
    }

    @Override
    public String toString() {
        return "MpqArchive[" + source.origin() + ", v" + header.formatVersion()
            + ", " + blockCount() + " blocks, " + names.size() + " named"
            + (header.malformed() ? ", malformed" : "") + "]";
    }
}
