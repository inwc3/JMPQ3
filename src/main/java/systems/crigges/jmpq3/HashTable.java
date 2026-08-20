package systems.crigges.jmpq3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MPQ hash table. Maps file paths to block table indices.
 * <p>
 * Supports localised files using Windows Language ID codes. When requesting a
 * localised mapping it prioritises the requested locale, then the default
 * locale, and finally the first locale found.
 * <p>
 * File paths are identified by a 64-bit key plus their bucket position, so the
 * hash table does not know which paths it contains. Archives therefore usually
 * ship a {@code (listfile)} naming them; that list can repopulate a hash table
 * of a different capacity with the same mappings.
 */
public class HashTable {
    private static final Logger LOG = LoggerFactory.getLogger(HashTable.class);

    /**
     * Magic block number representing a hash table entry which is not in use.
     */
    private static final int ENTRY_UNUSED = -1;

    /**
     * Magic block number representing a hash table entry which was deleted.
     */
    private static final int ENTRY_DELETED = -2;

    /**
     * Bits of a hash entry's block index that actually address the block table.
     * <p>
     * StormLib reads every block index through
     * {@code MPQ_BLOCK_INDEX(pHash) == (dwBlockIndex & BLOCK_INDEX_MASK)}; the
     * top nibble is reserved and appears set in archives produced by map
     * protectors. Without masking, such an archive looks like it has no files.
     * <p>
     * Source: StormLib {@code StormLib.h:274}.
     */
    public static final int BLOCK_INDEX_MASK = 0x0FFFFFFF;

    /**
     * The default file locale, US English.
     */
    public static final short DEFAULT_LOCALE = 0;

    /**
     * Hash table bucket array.
     */
    private final Bucket[] buckets;

    /**
     * The number of mappings in the hash table.
     */
    private int mappingNumber = 0;

    /**
     * Upper bound for a usable block index.
     * <p>
     * StormLib only accepts a hash entry whose block index is smaller than the
     * block table, which is what makes it skip the dangling entries protectors
     * plant. Left unbounded until the owning archive reads its block table.
     */
    private int blockTableSize = Integer.MAX_VALUE;

    /**
     * Construct an empty hash table with the specified capacity.
     * <p>
     * MPQ hash table capacities are powers of two. A non-power-of-two capacity
     * is accepted so that deliberately malformed archives can still be opened,
     * and is logged; StormLib's {@code hash & (capacity - 1)} indexing is used
     * either way, so lookups agree with the reference implementation whatever
     * the capacity.
     *
     * @param capacity capacity for the underlying bucket array.
     */
    public HashTable(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Hash table capacity must be positive, was " + capacity + ".");
        }
        if ((capacity & (capacity - 1)) != 0) {
            LOG.warn("Hash table capacity {} is not a power of two; StormLib assumes it is. "
                + "Indexing still follows StormLib's mask rule.", capacity);
        }

        buckets = new Bucket[capacity];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket();
        }
    }

    /**
     * @return the number of buckets.
     */
    public int capacity() {
        return buckets.length;
    }

    /**
     * @return the number of live mappings.
     */
    public int size() {
        return mappingNumber;
    }

    /**
     * Bounds which block indices count as live mappings.
     *
     * @param blockTableSize number of entries in the archive's block table.
     */
    public void setBlockTableSize(int blockTableSize) {
        this.blockTableSize = blockTableSize;
    }

    /**
     * Reads {@code capacity} buckets from a decrypted hash table image.
     *
     * @param src decrypted hash table bytes.
     */
    public void readFromBuffer(ByteBuffer src) {
        for (final Bucket entry : buckets) {
            entry.readFromBuffer(src);

            // count active mappings
            final int blockIndex = entry.blockTableIndex;
            if (blockIndex != ENTRY_UNUSED && blockIndex != ENTRY_DELETED) {
                entry.blockTableIndex = blockIndex & BLOCK_INDEX_MASK;
                mappingNumber++;
            }
        }
    }

    /**
     * Writes all buckets out, ready for encryption.
     *
     * @param dest destination buffer, at least {@code capacity * 16} bytes.
     */
    public void writeToBuffer(ByteBuffer dest) {
        for (Bucket bucket : buckets) {
            bucket.writeToBuffer(dest);
        }
    }

    /**
     * Internal method to get a bucket index for the specified file.
     *
     * @param file file identifier.
     * @return the bucket index used, or -1 if the file has no mapping.
     */
    private int getFileEntryIndex(FileIdentifier file) {
        int index = startIndex(file.offset);
        int bestEntryIndex = -1;

        for (int c = 0; c < buckets.length; c++) {
            final Bucket entry = buckets[index];

            if (entry.blockTableIndex == ENTRY_UNUSED) {
                break;
            } else if (entry.blockTableIndex == ENTRY_DELETED) {
                index = nextIndex(index);
                continue;
            } else if (entry.key == file.key && entry.blockTableIndex < blockTableSize) {
                if (entry.locale == file.locale) {
                    return index;
                } else if (bestEntryIndex == -1 || entry.locale == DEFAULT_LOCALE) {
                    bestEntryIndex = index;
                }
            }

            index = nextIndex(index);
        }

        return bestEntryIndex;
    }

    /**
     * Internal method to get a bucket for the specified file.
     *
     * @param file file identifier.
     * @return the file bucket, or null if the file has no mapping.
     */
    private Bucket getFileEntry(FileIdentifier file) {
        final int index = getFileEntryIndex(file);
        return index != -1 ? buckets[index] : null;
    }

    /**
     * Check if the specified file path has a mapping in this hash table.
     * <p>
     * A file path has a mapping if it has been mapped for at least 1 locale.
     *
     * @param file file path.
     * @return true if the hash table has a mapping for the file, otherwise
     *         false.
     */
    public boolean hasFile(String file) {
        return hasFile(file, DEFAULT_LOCALE);
    }

    /**
     * Check if the specified file path has a mapping in this hash table.
     *
     * @param file   file path.
     * @param locale preferred file locale.
     * @return true if the hash table has a mapping for the file, otherwise
     *         false.
     */
    public boolean hasFile(String file, short locale) {
        return getFileEntryIndex(new FileIdentifier(file, locale)) != -1;
    }

    /**
     * Get the block table index for the specified file.
     *
     * @param name file path name.
     * @return block table index.
     * @throws IOException if the specified file has no mapping.
     */
    public int getBlockIndexOfFile(String name) throws IOException {
        return getFileBlockIndex(name, DEFAULT_LOCALE);
    }

    /**
     * Get the block table index for the specified file.
     * <p>
     * Locale parameter is only a recommendation and the return result might be
     * for a different locale. When multiple locales are available the order of
     * priority for selection is the specified locale followed by the default
     * locale and lastly the first locale found.
     *
     * @param name   file path name.
     * @param locale file locale.
     * @return block table index.
     * @throws IOException if the specified file has no mapping.
     */
    public int getFileBlockIndex(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);
        final Bucket entry = getFileEntry(fid);

        if (entry == null) {
            throw new JMpqException("File Not Found <" + name + ">.");
        } else if (entry.blockTableIndex < 0) {
            throw new JMpqException("File has invalid block table index <" + entry.blockTableIndex + ">.");
        }

        return entry.blockTableIndex;
    }

    /**
     * @param name   file path name.
     * @param locale file locale.
     * @return the locale actually stored for this file, which may differ from
     *         the requested one.
     * @throws IOException if the specified file has no mapping.
     */
    public short getFileLocale(String name, short locale) throws IOException {
        final Bucket entry = getFileEntry(new FileIdentifier(name, locale));
        if (entry == null) {
            throw new JMpqException("File Not Found <" + name + ">.");
        }
        return entry.locale;
    }

    /**
     * Set a block table index for the specified file. Existing mappings are
     * updated.
     *
     * @param name       file path name.
     * @param locale     file locale.
     * @param blockIndex block table index.
     * @throws IOException if the mapping cannot be created.
     */
    public void setFileBlockIndex(String name, short locale, int blockIndex) throws IOException {
        if (blockIndex < 0 || blockIndex > BLOCK_INDEX_MASK) {
            throw new IllegalArgumentException(
                "Block index must be between 0 and " + BLOCK_INDEX_MASK + ", was " + blockIndex + ".");
        }

        final FileIdentifier fid = new FileIdentifier(name, locale);

        // check if file entry already exists
        final Bucket exist = getFileEntry(fid);
        if (exist != null && exist.locale == locale) {
            exist.blockTableIndex = blockIndex;
            return;
        }

        // check if space for new entry
        if (mappingNumber == buckets.length) {
            throw new JMpqException("Hash table cannot fit another mapping (capacity " + buckets.length + ").");
        }

        // locate suitable entry
        int index = startIndex(fid.offset);
        Bucket newEntry = null;
        for (int c = 0; c < buckets.length; c++) {
            final Bucket entry = buckets[index];

            if (entry.blockTableIndex == ENTRY_UNUSED || entry.blockTableIndex == ENTRY_DELETED) {
                newEntry = entry;
                break;
            }

            index = nextIndex(index);
        }

        // setup entry
        if (newEntry != null) {
            newEntry.key = fid.key;
            newEntry.locale = fid.locale;
            newEntry.blockTableIndex = blockIndex;
            mappingNumber++;
        }
    }

    /**
     * Internal method to remove a file entry at the specified bucket index.
     *
     * @param index bucket to clear.
     */
    private void removeFileEntry(int index) {
        final int bi = buckets[index].blockTableIndex;
        if (bi == ENTRY_UNUSED || bi == ENTRY_DELETED) {
            throw new IllegalArgumentException("Bucket already clear.");
        }

        // delete file
        final Bucket newEntry = new Bucket();
        newEntry.blockTableIndex = ENTRY_DELETED;
        buckets[index] = newEntry;
        mappingNumber--;

        // cleanup to empty if possible
        if (buckets[nextIndex(index)].blockTableIndex == ENTRY_UNUSED) {
            Bucket entry;
            int i = index;
            while ((entry = buckets[i]).blockTableIndex == ENTRY_DELETED) {
                entry.blockTableIndex = ENTRY_UNUSED;
                i = previousIndex(i);
            }
        }
    }

    /**
     * One live mapping in the table.
     *
     * @param key        the 64-bit MPQ file key the mapping is for.
     * @param locale     Windows Language ID of this variant.
     * @param blockIndex block table row holding the data.
     */
    public record Mapping(long key, short locale, int blockIndex) {
    }

    /**
     * Every live mapping, in bucket order.
     * <p>
     * The table stores hashes rather than names, so a caller correlates these
     * with names it already knows by comparing {@link Mapping#key()} against
     * {@code MpqNames.fileKey(name)}. That is the only way to discover all
     * locale variants of a path: a lookup resolves one variant by the format's
     * preference order and cannot report the others.
     *
     * @return the live mappings.
     */
    public java.util.List<Mapping> mappings() {
        final java.util.List<Mapping> live = new java.util.ArrayList<>(mappingNumber);
        for (Bucket bucket : buckets) {
            if (bucket.blockTableIndex != ENTRY_UNUSED && bucket.blockTableIndex != ENTRY_DELETED
                && bucket.blockTableIndex < blockTableSize) {
                live.add(new Mapping(bucket.key, bucket.locale, bucket.blockTableIndex));
            }
        }
        return live;
    }

    /**
     * Maps a bucket-offset hash to a starting bucket, using StormLib's rule.
     * <p>
     * StormLib indexes with {@code hash & (dwHashTableSize - 1)}
     * ({@code HASH_INDEX_MASK}), which for a power-of-two capacity is the
     * unsigned remainder. It is kept even for the non-power-of-two capacities
     * this class tolerates: whatever wrote such a table did so with the mask
     * rule, so probing from a remainder-derived bucket would start in the wrong
     * place and, because the probe stops at the first unused bucket, could
     * report present files as missing. The mask can never exceed
     * {@code capacity - 1}, so the result is always in range.
     */
    private int startIndex(int offsetHash) {
        return offsetHash & (buckets.length - 1);
    }

    private int nextIndex(int index) {
        return index + 1 == buckets.length ? 0 : index + 1;
    }

    private int previousIndex(int index) {
        return index == 0 ? buckets.length - 1 : index - 1;
    }

    /**
     * Remove the specified file from the hash table.
     *
     * @param name   file path name.
     * @param locale file locale.
     * @throws IOException if the file cannot be found.
     */
    public void removeFile(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);

        // check if file exists
        final int index = getFileEntryIndex(fid);
        if (index == -1 || buckets[index].locale != locale) {
            throw new JMpqException("File Not Found <" + name + ">");
        }

        // delete file
        removeFileEntry(index);
    }

    /**
     * Remove the specified file from the hash table for all locales.
     *
     * @param name file path name.
     * @return number of file entries that were removed.
     * @throws IOException if no file entries were found.
     */
    public int removeFileAll(String name) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, DEFAULT_LOCALE);
        int count = 0;
        int index;
        while ((index = getFileEntryIndex(fid)) != -1) {
            removeFileEntry(index);
            count++;
        }

        // check if file was removed
        if (count == 0) {
            throw new JMpqException("File Not Found <" + name + ">");
        }

        return count;
    }

    /**
     * A uniquely identifiable file, caching the file name hash results.
     *
     * @param key    64-bit file key.
     * @param offset offset into the bucket array at which to start searching.
     * @param locale file locale as a Windows Language ID.
     */
    private record FileIdentifier(long key, int offset, short locale) {
        FileIdentifier(String name, short locale) {
            this(MpqNames.fileKey(name), MpqNames.tableOffset(name), locale);
        }
    }

    /**
     * Plain old data class for hash table buckets.
     */
    private static final class Bucket {
        /**
         * 64 bit file key.
         */
        private long key = 0;

        /**
         * File locale in the form of a Windows Language ID.
         */
        private short locale = 0;

        /**
         * Block table index for file data.
         * <p>
         * Some negative magic numbers are used to represent the bucket state.
         */
        private int blockTableIndex = ENTRY_UNUSED;

        Bucket() {
        }

        void readFromBuffer(ByteBuffer src) {
            src.order(ByteOrder.LITTLE_ENDIAN);
            key = src.getLong();
            locale = src.getShort();
            src.getShort(); // platform not used
            blockTableIndex = src.getInt();
        }

        void writeToBuffer(ByteBuffer dest) {
            dest.order(ByteOrder.LITTLE_ENDIAN);
            dest.putLong(key);
            dest.putShort(locale);
            dest.putShort((short) 0); // platform not used
            dest.putInt(blockTableIndex);
        }

        @Override
        public String toString() {
            return "Entry [key=" + key + ",\tlcLocale=" + this.locale + ",\tdwBlockIndex=" + this.blockTableIndex + "]";
        }
    }

    /**
     * @param name file path name.
     * @return the 64-bit MPQ file key.
     * @deprecated use {@link MpqNames#fileKey(String)}; kept so existing
     *             callers keep compiling.
     */
    @Deprecated
    public static long calculateFileKey(String name) {
        return MpqNames.fileKey(name);
    }
}
