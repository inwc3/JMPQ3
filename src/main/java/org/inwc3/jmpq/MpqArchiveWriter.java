package org.inwc3.jmpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.JMpqException;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Builds an MPQ archive and writes it somewhere, when told to.
 *
 * <h2>Saving is explicit</h2>
 * Nothing is written until {@link #save(Path)} or a sibling is called. The
 * pre-2.0 {@code JMpqEditor} rebuilt the archive as a side effect of
 * {@code close()}, so a missed flag, an exception on the way out, or simply
 * opening an archive to read it could rewrite the file. Here, reading is
 * {@link MpqArchive} and writing is this class, and the write happens where the
 * caller asks for it.
 *
 * <h2>Built in memory</h2>
 * The whole image is assembled in memory and handed to the destination in one
 * write. No temporary files, no shared staging directory, and no mapped output
 * whose size has to be guessed before compression has happened.
 *
 * <h2>Copying versus re-encoding</h2>
 * A file taken from an existing archive is copied with its stored bytes intact
 * when the target keeps the source's sector size, and decoded and re-encoded
 * otherwise. That is not an optimisation: a sector offset table is expressed in
 * the archive's sector size, so copying stored bytes into an archive with a
 * different sector size produces a file no reader can decode.
 *
 * <h2>Thread safety</h2>
 * Not thread safe. One writer belongs to one thread.
 */
public final class MpqArchiveWriter {
    private static final Logger log = LoggerFactory.getLogger(MpqArchiveWriter.class);

    private static final int KEY_HASH_TABLE = tableKey("(hash table)");
    private static final int KEY_BLOCK_TABLE = tableKey("(block table)");

    /**
     * Internal files the writer always generates itself, so a caller cannot
     * supply them: doing so would put two entries under one name.
     * <p>
     * Only {@code (listfile)} is unconditional. {@code (attributes)} is
     * generated only when asked for, so supplying it stays legal otherwise and
     * is refused at build time when both are requested. {@code (signature)} is
     * never generated, so a caller holding those bytes may write them as an
     * ordinary file.
     */
    private static final List<String> GENERATED = List.of("(listfile)");

    /**
     * Internal files carried over from a source archive is not attempted:
     * {@code (listfile)} is regenerated, and the other two cannot be
     * regenerated so a copy would be stale.
     */
    private static final List<String> NOT_CARRIED_OVER =
        List.of("(listfile)", "(attributes)", "(signature)");

    /**
     * Flags the writer gives the internal files it generates, matching what
     * StormLib gives them: compressed, encrypted, and with the key adjusted for
     * position so moving the file invalidates it.
     */
    private static final int INTERNAL_FILE_FLAGS = MpqFileEntry.FLAG_EXISTS
        | MpqFileEntry.FLAG_COMPRESSED
        | MpqFileEntry.FLAG_ENCRYPTED
        | MpqFileEntry.FLAG_ADJUSTED_KEY;

    private static int tableKey(String name) {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process(name);
        return hasher.getHash();
    }

    /** Where a pending file's content comes from. */
    private sealed interface Content {
        /** Content already in memory. Copied on insert, never aliased. */
        record Bytes(byte[] value) implements Content {
        }

        /** Content read from disk at save time. */
        record File(Path path) implements Content {
        }

        /** Content carried over from an archive being rebuilt. */
        record Existing(MpqArchive archive, MpqFileEntry entry) implements Content {
        }
    }

    /**
     * @param name    the file's path, as the caller spelled it.
     * @param locale  Windows Language ID this variant is stored under.
     * @param content where its bytes come from.
     */
    private record Pending(String name, short locale, Content content) {
    }

    /**
     * A pending file's identity: MPQ can hold several localised variants of one
     * path, so the name alone does not identify a file. Keying on the name
     * alone silently dropped every variant but one on rebuild, and relabelled a
     * surviving non-neutral variant as neutral.
     *
     * @param canonicalName case-folded path.
     * @param locale        Windows Language ID.
     */
    private record Key(String canonicalName, short locale) {
    }

    private final MpqWriteOptions options;

    /** Pending files, keyed on canonical name, in insertion order. */
    private final SequencedMap<Key, Pending> pending = new LinkedHashMap<>();

    /** Bytes preceding the archive header in the source, if any are kept. */
    private byte[] prefix = new byte[0];

    private MpqArchiveWriter(MpqWriteOptions options) {
        this.options = options;
    }

    /**
     * Starts an empty archive.
     *
     * @param options how to build it.
     * @return a new writer.
     */
    public static MpqArchiveWriter create(MpqWriteOptions options) {
        return new MpqArchiveWriter(options);
    }

    /**
     * Starts from an existing archive's contents.
     * <p>
     * Only files the source can name are carried over: an archive without a
     * usable list file cannot enumerate itself, so a rebuild would silently
     * drop the rest. That limitation is the archive's, not this writer's, and
     * {@link #from(MpqArchive, MpqWriteOptions)} reports how many files it took.
     *
     * @param source  archive to copy from; must stay open until
     *                {@link #save(Path)} runs, because file content is read
     *                lazily.
     * @param options how to build the result.
     * @return a writer holding the source's named files.
     * @throws IOException if the source cannot be enumerated.
     */
    public static MpqArchiveWriter from(MpqArchive source, MpqWriteOptions options) throws IOException {
        final MpqArchiveWriter writer = new MpqArchiveWriter(options);
        if (options.keepPrefix() && source.header().headerOffset() > 0) {
            writer.prefix = source.prefixBytes();
        }
        for (String name : source.names()) {
            if (NOT_CARRIED_OVER.contains(name)) {
                continue;
            }
            // Every locale variant, not just the one a lookup resolves.
            for (short locale : source.localesOf(name)) {
                final MpqFileEntry entry = source.entry(name, locale).orElse(null);
                if (entry == null || entry.locale() != locale) {
                    continue;
                }
                writer.pending.put(new Key(MpqNames.canonical(name), locale),
                    new Pending(name, locale, new Content.Existing(source, entry)));
            }
        }
        final int dropped = source.filesLostOnRebuild();
        if (dropped > 0) {
            // Stated plainly, because it is data loss the caller may not
            // expect: these blocks exist but nothing names them, so the rebuilt
            // archive cannot contain them.
            log.warn("{} of the {} files in {} cannot be named and will not be carried over."
                + " Supply a list file covering them if they matter.",
                dropped, source.blockCount(), source);
        }
        log.debug("Writer seeded with {} files from {}", writer.pending.size(), source);
        return writer;
    }

    /**
     * Adds or replaces a file.
     * <p>
     * The array is copied, so the caller may reuse it.
     *
     * @param name    path inside the archive.
     * @param content the file's bytes.
     * @return this writer.
     */
    public MpqArchiveWriter put(String name, byte[] content) {
        return put(name, MpqOpenOptions.NEUTRAL_LOCALE, content);
    }

    /**
     * Adds or replaces a localised variant of a file.
     *
     * @param name    path inside the archive.
     * @param locale  Windows Language ID; 0 is the neutral default.
     * @param content the file's bytes, copied on insert.
     * @return this writer.
     */
    public MpqArchiveWriter put(String name, short locale, byte[] content) {
        requireUsableName(name);
        pending.put(new Key(MpqNames.canonical(name), locale),
            new Pending(name, locale, new Content.Bytes(content.clone())));
        return this;
    }

    /**
     * Adds or replaces a file, reading it from disk at save time.
     *
     * @param name path inside the archive.
     * @param file source file; must exist when {@link #save(Path)} runs.
     * @return this writer.
     */
    public MpqArchiveWriter put(String name, Path file) {
        return put(name, MpqOpenOptions.NEUTRAL_LOCALE, file);
    }

    /**
     * Adds or replaces a localised variant, read from disk at save time.
     *
     * @param name   path inside the archive.
     * @param locale Windows Language ID.
     * @param file   source file.
     * @return this writer.
     */
    public MpqArchiveWriter put(String name, short locale, Path file) {
        requireUsableName(name);
        pending.put(new Key(MpqNames.canonical(name), locale),
            new Pending(name, locale, new Content.File(file)));
        return this;
    }

    /**
     * Removes a file.
     *
     * @param name path inside the archive.
     * @return whether it was present.
     */
    public boolean remove(String name) {
        // Removes every locale variant: a caller naming a path without a locale
        // means the path, not one translation of it.
        return pending.keySet().removeIf(key -> key.canonicalName().equals(MpqNames.canonical(name)));
    }

    /**
     * Removes one localised variant.
     *
     * @param name   path inside the archive.
     * @param locale Windows Language ID.
     * @return whether it was present.
     */
    public boolean remove(String name, short locale) {
        return pending.remove(new Key(MpqNames.canonical(name), locale)) != null;
    }

    /**
     * @param name path inside the archive.
     * @return whether the archive being built holds it.
     */
    public boolean contains(String name) {
        final String canonical = MpqNames.canonical(name);
        return pending.keySet().stream().anyMatch(key -> key.canonicalName().equals(canonical));
    }

    /**
     * @param name   path inside the archive.
     * @param locale Windows Language ID.
     * @return whether that variant will be written.
     */
    public boolean contains(String name, short locale) {
        return pending.containsKey(new Key(MpqNames.canonical(name), locale));
    }

    /**
     * @return the names to be written, in insertion order.
     */
    public List<String> names() {
        return pending.values().stream().map(Pending::name).toList();
    }

    private void requireUsableName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A file name is required.");
        }
        for (String generated : GENERATED) {
            if (generated.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException(name + " is generated by the writer and cannot"
                    + " be supplied. Use MpqWriteOptions.withListfile to control it.");
            }
        }
    }

    /**
     * Builds the archive and returns its bytes.
     *
     * @return the finished archive image.
     * @throws IOException if a source file cannot be read, or the result cannot
     *                     be represented.
     */
    public byte[] toByteArray() throws IOException {
        return build().toByteArray();
    }

    /**
     * Builds the archive and writes it to a stream.
     *
     * @param target destination; flushed but not closed.
     * @throws IOException if the build or the write fails.
     */
    public void save(OutputStream target) throws IOException {
        build().writeTo(target);
    }

    /**
     * Builds the archive and writes it to a file.
     * <p>
     * The image is written to a sibling temporary file and then moved into
     * place, so an interrupted save cannot leave a half-written archive where a
     * working one used to be.
     * <p>
     * The destination must not be an archive that is still open, because a
     * mapped file cannot be replaced on Windows. To rebuild an archive over
     * itself, build the image while the source is open and write it after
     * closing it:
     * <pre>
     * byte[] image;
     * try (MpqArchive source = MpqArchive.open(path, readOptions)) {
     *     image = MpqArchiveWriter.from(source, writeOptions).put(...).toByteArray();
     * }
     * Files.write(path, image);
     * </pre>
     * {@link #from(MpqArchive, MpqWriteOptions)} reads file content lazily, so
     * the source has to stay open until the image is built.
     *
     * @param target destination file; created or replaced. Must not be open
     *               elsewhere.
     * @throws IOException if the build or the write fails.
     */
    public void save(Path target) throws IOException {
        final MpqImageBuffer image = build();
        final Path directory = target.toAbsolutePath().getParent();
        final Path staging = Files.createTempFile(directory, ".jmpq-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(staging)) {
                image.writeTo(out);
            }
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /**
     * Assembles the whole image.
     * <p>
     * Layout is prefix, header, file data, hash table, block table. The header
     * is written last, because it records where the tables ended up.
     */
    private MpqImageBuffer build() throws IOException {
        final int sectorSize = options.sectorSize();
        final int headerSize = options.headerSize();

        final MpqImageBuffer image = new MpqImageBuffer(estimateSize());
        image.put(prefix);
        final int base = image.position();

        // Reserve the header; its contents depend on where the tables land.
        image.skip(headerSize);

        // Name plus locale, because the hash table needs both and a path
        // may appear once per locale.
        if (options.writeAttributes() && contains(MpqAttributes.NAME)) {
            throw new JMpqException("Cannot both generate " + MpqAttributes.NAME
                + " and write a supplied one: the archive would hold two entries under that"
                + " name. Either drop the supplied file or turn attributes generation off.");
        }

        final List<Written> written = new ArrayList<>(pending.size() + 2);
        final List<BlockRow> blocks = new ArrayList<>(pending.size() + 2);
        // One CRC32 per block, in block order, for the (attributes) file. Left
        // empty when attributes are not requested, so nothing is decoded for
        // the sake of a checksum nobody asked for.
        final List<Integer> checksums = new ArrayList<>(pending.size() + 2);

        for (Pending file : pending.values()) {
            // Taken before writing, because a verbatim copy never decodes the
            // file and the checksum is over its decoded content.
            checksums.add(options.writeAttributes() ? crc32(contentOf(file)) : 0);
            blocks.add(writeFile(image, base, file, sectorSize));
            written.add(new Written(file.name(), file.locale()));
        }

        if (options.writeListfile()) {
            // Written even when empty: an archive with no (listfile) cannot be
            // enumerated, so a later rebuild would lose every name.
            final byte[] listfile = buildListfile(written);
            written.add(new Written("(listfile)", MpqOpenOptions.NEUTRAL_LOCALE));
            checksums.add(options.writeAttributes() ? crc32(listfile) : 0);
            blocks.add(writeEncoded(image, base, "(listfile)", listfile, sectorSize,
                INTERNAL_FILE_FLAGS));
        }

        if (options.writeAttributes()) {
            // Its own arrays have to be sized before it is written, and they
            // cover every block table row -- including this file's own and any
            // spare slots. Its own checksum stays 0, which cannot be computed
            // without knowing it, and which readers treat as "not recorded".
            final int rows = blocks.size() + 1 + options.extraBlockEntries();
            final byte[] attributes = buildAttributes(checksums, rows);
            written.add(new Written(MpqAttributes.NAME, MpqOpenOptions.NEUTRAL_LOCALE));
            blocks.add(writeEncoded(image, base, MpqAttributes.NAME, attributes, sectorSize,
                INTERNAL_FILE_FLAGS));
        }

        final int hashCapacity = hashTableCapacity(written.size());
        final int blockCapacity = blocks.size() + options.extraBlockEntries();

        final long hashPosition = (long) image.position() - base;
        final long blockPosition = hashPosition + (long) hashCapacity * MpqHeader.HASH_ENTRY_SIZE;

        writeHashTable(image, written, hashCapacity);
        writeBlockTable(image, blocks, blockCapacity);

        final long archiveSize = (long) image.size() - base;
        writeHeader(image, base, headerSize, archiveSize, hashPosition, blockPosition,
            hashCapacity, blockCapacity);
        return image;
    }

    /** One block table row, as built. */
    private record BlockRow(long filePosition, int compressedSize, int normalSize, int flags) {
    }

    /**
     * A name as it will be registered in the rebuilt hash table.
     *
     * @param name   path inside the archive.
     * @param locale Windows Language ID this variant occupies.
     */
    private record Written(String name, short locale) {
    }

    private BlockRow writeFile(MpqImageBuffer image, int base, Pending file, int sectorSize)
        throws IOException {
        if (file.content() instanceof Content.Existing existing
            && canCopyVerbatim(existing.archive(), sectorSize)) {
            return copyVerbatim(image, base, file.name(), existing);
        }
        return writeEncoded(image, base, file.name(), contentOf(file), sectorSize, 0);
    }

    /**
     * Whether a file from {@code source} can keep its stored bytes.
     * <p>
     * Only when the sector size matches. Copying a sector offset table into an
     * archive with a different sector size leaves the table describing the old
     * geometry, and the file becomes unreadable — the exact bug the golden
     * harness caught in the pre-2.0 recompression path.
     */
    private boolean canCopyVerbatim(MpqArchive source, int sectorSize) {
        return source.header().sectorSize() == sectorSize
            && !options.recompression().recompress;
    }

    private BlockRow copyVerbatim(MpqImageBuffer image, int base, String name,
                                  Content.Existing existing) throws IOException {
        final MpqFileEntry entry = existing.entry();
        final long position = (long) image.position() - base;
        final byte[] stored = existing.archive().storedBytesDecrypted(entry);
        image.put(stored);

        // The bytes are now plain, so the flags must stop claiming otherwise.
        // Everything else about the encoding is preserved, which keeps the
        // sector offset table and any per-sector checksums valid.
        final int flags = (entry.flags() | MpqFileEntry.FLAG_EXISTS)
            & ~(MpqFileEntry.FLAG_ENCRYPTED | MpqFileEntry.FLAG_ADJUSTED_KEY);
        return new BlockRow(position, stored.length, entry.normalSize(), flags);
    }

    private BlockRow writeEncoded(MpqImageBuffer image, int base, String name, byte[] content,
                                  int sectorSize, int requestedFlags) {
        final long position = (long) image.position() - base;
        int flags = requestedFlags == 0
            ? MpqSectorWriter.flagsFor(content.length, options.sectorChecksums())
            : requestedFlags;
        if (options.sectorChecksums() && content.length > 0) {
            flags |= MpqFileEntry.FLAG_SECTOR_CRC;
        }
        final int compressedSize = MpqSectorWriter.write(image, content, sectorSize, name,
            flags, position, options.recompression());
        return new BlockRow(position, compressedSize, content.length, flags);
    }

    /**
     * Builds the {@code (attributes)} content.
     * <p>
     * The arrays are indexed by block table row and cover every row the archive
     * will have, so spare slots get a zero checksum and a zero timestamp --
     * which is what "not recorded" looks like to a reader.
     *
     * @param checksums one CRC32 per block written so far.
     * @param rows      total block table rows the archive will declare.
     * @return the attributes file content.
     */
    private byte[] buildAttributes(List<Integer> checksums, int rows) {
        final int[] crc = new int[rows];
        final long[] times = new long[rows];
        final long now = options.metadata().fileTime();
        for (int i = 0; i < rows; i++) {
            final boolean real = i < checksums.size();
            crc[i] = real ? checksums.get(i) : 0;
            times[i] = real ? now : 0;
        }
        return MpqAttributes.build(crc, times);
    }

    /**
     * @param content a file's decoded bytes.
     * @return its zlib CRC32, the value {@code (attributes)} records.
     */
    private static int crc32(byte[] content) {
        final java.util.zip.CRC32 digest = new java.util.zip.CRC32();
        digest.update(content);
        return (int) digest.getValue();
    }

    private byte[] contentOf(Pending file) throws IOException {
        return switch (file.content()) {
            case Content.Bytes bytes -> bytes.value();
            case Content.File source -> Files.readAllBytes(source.path());
            case Content.Existing existing -> existing.archive().read(existing.entry());
        };
    }

    /**
     * A list file names paths, not locale variants, so a path localised
     * several times still appears once.
     */
    private byte[] buildListfile(List<Written> names) {
        final java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (Written entry : names) {
            unique.add(entry.name());
        }
        final StringBuilder out = new StringBuilder(unique.size() * 32);
        for (String name : unique) {
            out.append(name).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sizes the hash table.
     * <p>
     * Twice the next power of two above the file count, which keeps the load
     * factor at or below 50% so probe chains stay short. An explicit capacity
     * overrides it, for tooling that wants a maximised table.
     */
    private int hashTableCapacity(int fileCount) throws JMpqException {
        if (options.hashTableCapacity() > 0) {
            if (options.hashTableCapacity() < fileCount) {
                throw new JMpqException("Hash table capacity " + options.hashTableCapacity()
                    + " cannot hold " + fileCount + " files.");
            }
            return options.hashTableCapacity();
        }
        int capacity = 4;
        while (capacity < (fileCount + 2) * 2) {
            capacity <<= 1;
            if (capacity > MpqHeader.MAX_HASH_TABLE_ENTRIES) {
                throw new JMpqException("Cannot fit " + fileCount + " files: the hash table would"
                    + " exceed the " + MpqHeader.MAX_HASH_TABLE_ENTRIES + " entry maximum.");
            }
        }
        return capacity;
    }

    private void writeHashTable(MpqImageBuffer image, List<Written> names, int capacity)
        throws IOException {
        final HashTable table = new HashTable(capacity);
        int blockIndex = 0;
        for (Written entry : names) {
            // Each variant keeps its own locale. Registering everything as
            // neutral collapsed localised variants onto one bucket, so all
            // but one were lost and the survivor was relabelled.
            table.setFileBlockIndex(entry.name(), entry.locale(), blockIndex++);
        }

        // capacity is bounded by MAX_HASH_TABLE_ENTRIES (0x80000), so 8 MiB at
        // most; stated here because the same shape overflows for block tables.
        final ByteBuffer buffer = ByteBuffer.allocate(capacity * MpqHeader.HASH_ENTRY_SIZE);
        table.writeToBuffer(buffer);
        buffer.flip();
        new MPQEncryption(KEY_HASH_TABLE, false).processSingle(buffer);
        buffer.flip();
        image.put(buffer);
    }

    private void writeBlockTable(MpqImageBuffer image, List<BlockRow> blocks, int capacity)
        throws JMpqException {
        // Block capacity is caller-influenced through extraBlockEntries, so
        // capacity * 16 can overflow int where the hash table's cannot.
        final long tableBytes = (long) capacity * MpqHeader.BLOCK_ENTRY_SIZE;
        if (tableBytes > MpqImageBuffer.MAX_SIZE) {
            throw new JMpqException("A block table of " + capacity + " entries needs "
                + tableBytes + " bytes, more than an archive image can hold.");
        }
        final ByteBuffer plain = ByteBuffer
            .allocate((int) tableBytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (BlockRow block : blocks) {
            plain.putInt((int) block.filePosition());
            plain.putInt(block.compressedSize());
            plain.putInt(block.normalSize());
            plain.putInt(block.flags());
        }
        // Unused slots stay zero, which reads as a block that does not exist.
        plain.clear();

        final ByteBuffer encrypted = ByteBuffer
            .allocate((int) tableBytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        new MPQEncryption(KEY_BLOCK_TABLE, false).processFinal(plain, encrypted);
        encrypted.flip();
        image.put(encrypted);
    }

    private void writeHeader(MpqImageBuffer image, int base, int headerSize, long archiveSize,
                             long hashPosition, long blockPosition, int hashCapacity,
                             int blockCapacity) throws JMpqException {
        if (options.formatVersion() == 0) {
            requireUnsigned32(archiveSize, "Archive size");
            requireUnsigned32(hashPosition, "Hash table position");
            requireUnsigned32(blockPosition, "Block table position");
        }

        final ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MpqHeader.ARCHIVE_SIGNATURE);
        header.putInt(headerSize);
        header.putInt((int) archiveSize);
        header.putShort((short) options.formatVersion());
        header.putShort((short) options.sectorSizeShift());
        header.putInt((int) hashPosition);
        header.putInt((int) blockPosition);
        header.putInt(hashCapacity);
        header.putInt(blockCapacity);

        if (options.formatVersion() >= 1) {
            header.putLong(0); // no hi-block table
            header.putShort((short) (hashPosition >>> 32));
            header.putShort((short) (blockPosition >>> 32));
        }

        image.putAt(base, header.array());
    }

    private static void requireUnsigned32(long value, String field) throws JMpqException {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new JMpqException(field + " is " + value
                + ", beyond what a format version 0 header can express. Write version 1 instead.");
        }
    }

    /**
     * Rough starting size, so the buffer does not have to double repeatedly.
     * Being wrong is harmless.
     */
    private int estimateSize() {
        long estimate = prefix.length + options.headerSize() + 4096;
        for (Pending file : pending.values()) {
            estimate += switch (file.content()) {
                case Content.Bytes bytes -> bytes.value().length;
                case Content.Existing existing -> existing.entry().compressedSize();
                case Content.File ignored -> 0;
            };
        }
        return (int) Math.min(estimate, MpqImageBuffer.MAX_SIZE);
    }
}
