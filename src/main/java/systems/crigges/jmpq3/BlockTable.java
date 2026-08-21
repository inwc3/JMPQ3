package systems.crigges.jmpq3;

import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static systems.crigges.jmpq3.MpqFile.COMPRESSED;
import static systems.crigges.jmpq3.MpqFile.DELETED;
import static systems.crigges.jmpq3.MpqFile.ENCRYPTED;
import static systems.crigges.jmpq3.MpqFile.EXISTS;
import static systems.crigges.jmpq3.MpqFile.IMPLODED;
import static systems.crigges.jmpq3.MpqFile.SECTOR_CRC;
import static systems.crigges.jmpq3.MpqFile.SINGLE_UNIT;
import static systems.crigges.jmpq3.MpqFile.ADJUSTED_ENCRYPTED;

/**
 * MPQ block table. Holds the position, sizes and flags of every file in the
 * archive, addressed by the index the hash table stores.
 */
public class BlockTable {
    /** Encryption key for block table data: hash of {@code "(block table)"}. */
    static final int KEY_BLOCK_TABLE = -326913117;

    /** Size of one block table entry in bytes. */
    public static final int ENTRY_SIZE = 16;

    private final ByteBuffer blockMap;
    private final int size;

    /**
     * @param buf encrypted block table image; its capacity determines the entry
     *            count.
     * @throws IOException if the table cannot be decrypted.
     */
    public BlockTable(ByteBuffer buf) throws IOException {
        this.size = buf.capacity() / ENTRY_SIZE;

        blockMap = ByteBuffer.allocate(buf.capacity());
        new MPQEncryption(KEY_BLOCK_TABLE, true).processFinal(buf, blockMap);
        this.blockMap.order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Wraps an already-decoded set of rows, for the deprecated
     * {@code JMpqEditor} adapter, which obtains its rows from the new core
     * rather than by decrypting the table itself.
     *
     * @param rows block table rows in table order.
     * @return a block table over those rows.
     */
    public static BlockTable of(List<Block> rows) {
        final ByteBuffer plain = ByteBuffer.allocate(rows.size() * ENTRY_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (Block row : rows) {
            row.writeToBuffer(plain);
        }
        plain.clear();
        // The constructor decrypts, so hand it an encrypted image built from
        // these rows rather than duplicating the decoding logic here.
        final ByteBuffer encrypted = ByteBuffer.allocate(rows.size() * ENTRY_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        new MPQEncryption(KEY_BLOCK_TABLE, false).processFinal(plain, encrypted);
        encrypted.rewind();
        try {
            return new BlockTable(encrypted);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot rebuild an in-memory block table.", e);
        }
    }

    /**
     * @return number of entries, live or not.
     */
    public int size() {
        return size;
    }

    /**
     * Encrypts and writes a fresh block table.
     *
     * @param blocks entries to write, in order.
     * @param size   number of entry slots to emit; must be at least
     *               {@code blocks.size()}.
     * @param buf    destination.
     */
    public static void writeNewBlocktable(List<Block> blocks, int size, ByteBuffer buf) {
        if (blocks.size() > size) {
            throw new IllegalArgumentException(
                "Block table sized for " + size + " entries cannot hold " + blocks.size() + ".");
        }
        ByteBuffer temp = ByteBuffer.allocate(size * ENTRY_SIZE);
        temp.order(ByteOrder.LITTLE_ENDIAN);
        for (Block b : blocks) {
            b.writeToBuffer(temp);
        }
        temp.clear();
        if (new MPQEncryption(KEY_BLOCK_TABLE, false).processFinal(temp, buf)) {
            throw new BufferOverflowException();
        }
    }

    /**
     * @param pos block table index.
     * @return the entry at that index.
     * @throws JMpqException if {@code pos} is outside the table.
     */
    public Block getBlockAtPos(int pos) throws JMpqException {
        // Note the '>=': index 'size' is one past the end. The old '>' let a
        // read run off the end of the table and produce a garbage block.
        if (pos < 0 || pos >= this.size) {
            throw new JMpqException("Invalid block position " + pos + " (table holds " + this.size + " entries).");
        }
        this.blockMap.position(pos * ENTRY_SIZE);
        try {
            return new Block(this.blockMap);
        } catch (IOException e) {
            throw new JMpqException("Cannot read block " + pos + ".", e);
        }
    }

    /**
     * @return every entry carrying the {@code EXISTS} flag.
     * @throws JMpqException if the table cannot be read.
     */
    public ArrayList<Block> getAllValidBlocks() throws JMpqException {
        ArrayList<Block> list = new ArrayList<>();
        for (int i = 0; i < this.size; i++) {
            Block b = getBlockAtPos(i);
            if (b.hasFlag(EXISTS)) {
                list.add(b);
            }
        }
        return list;
    }

    /**
     * @return every entry carrying the {@code EXISTS} flag.
     * @deprecated misspelled; use {@link #getAllValidBlocks()}.
     */
    @Deprecated
    public ArrayList<Block> getAllVaildBlocks() throws JMpqException {
        return getAllValidBlocks();
    }

    /**
     * One block table entry: where a file's data lives and how it is encoded.
     */
    public static class Block {
        /**
         * Offset of the file data relative to the archive header.
         * <p>
         * Kept as a {@code long} end to end. Format version 1 and above can
         * place a file beyond 4 GiB using the hi-word fields, and the old
         * {@code int} accessor truncated such offsets.
         */
        private long filePos;
        private int compressedSize;
        private int normalSize;
        private int flags;

        /**
         * Reads one entry from a decrypted block table image.
         *
         * @param buf positioned at the start of the entry.
         * @throws IOException never thrown; kept for source compatibility.
         */
        public Block(ByteBuffer buf) throws IOException {
            this.filePos = buf.getInt() & 0xFFFFFFFFL;
            this.compressedSize = buf.getInt();
            this.normalSize = buf.getInt();
            this.flags = buf.getInt();
        }

        public Block(long filePos, int compressedSize, int normalSize, int flags) {
            this.filePos = filePos;
            this.compressedSize = compressedSize;
            this.normalSize = normalSize;
            this.flags = flags;
        }

        public void writeToBuffer(ByteBuffer bb) {
            bb.putInt((int) this.filePos);
            bb.putInt(this.compressedSize);
            bb.putInt(this.normalSize);
            bb.putInt(this.flags);
        }

        /**
         * @return the file offset relative to the archive header, truncated to
         *         32 bits.
         * @deprecated truncates offsets above 4 GiB; use
         *             {@link #getFilePosition()}.
         */
        @Deprecated
        public int getFilePos() {
            return (int) this.filePos;
        }

        /**
         * @return the file offset relative to the archive header.
         */
        public long getFilePosition() {
            return this.filePos;
        }

        /**
         * @return the number of bytes the file occupies in the archive.
         */
        public int getCompressedSize() {
            return this.compressedSize;
        }

        /**
         * @return the file's size once decompressed.
         */
        public int getNormalSize() {
            return this.normalSize;
        }

        public int getFlags() {
            return this.flags;
        }

        public void setFilePos(long filePos) {
            this.filePos = filePos;
        }

        public void setCompressedSize(int compressedSize) {
            this.compressedSize = compressedSize;
        }

        public void setNormalSize(int normalSize) {
            this.normalSize = normalSize;
        }

        public void setFlags(int flags) {
            this.flags = flags;
        }

        /**
         * @param flag one or more flag bits.
         * @return whether <em>all</em> given bits are set.
         */
        public boolean hasFlag(int flag) {
            return (flags & flag) == flag;
        }

        /**
         * Whether this file's data is preceded by a sector offset table.
         * <p>
         * Derived from the flags, per spec: a sector offset table exists when
         * the file is split into sectors and compressed or imploded. Single
         * unit files hold one contiguous blob, and files stored verbatim need
         * no offsets. The old code inferred this from a sector count of 1,
         * which conflated "no offset table" with "empty file".
         *
         * @return true when the file data starts with a sector offset table.
         */
        public boolean hasSectorOffsetTable() {
            return !hasFlag(SINGLE_UNIT) && (hasFlag(COMPRESSED) || hasFlag(IMPLODED));
        }

        @Override
        public String toString() {
            return "Block [filePos=" + this.filePos + ", compressedSize=" + this.compressedSize
                + ", normalSize=" + this.normalSize + ", flags=" + printFlags().trim() + "]";
        }

        public String printFlags() {
            return (hasFlag(EXISTS) ? "EXISTS " : "")
                + (hasFlag(SINGLE_UNIT) ? "SINGLE_UNIT " : "")
                + (hasFlag(COMPRESSED) ? "COMPRESSED " : "")
                + (hasFlag(IMPLODED) ? "IMPLODED " : "")
                + (hasFlag(ENCRYPTED) ? "ENCRYPTED " : "")
                + (hasFlag(ADJUSTED_ENCRYPTED) ? "ADJUSTED " : "")
                + (hasFlag(SECTOR_CRC) ? "SECTOR_CRC " : "")
                + (hasFlag(DELETED) ? "DELETED " : "");
        }
    }
}
