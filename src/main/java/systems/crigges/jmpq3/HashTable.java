package systems.crigges.jmpq3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import systems.crigges.jmpq3.security.MPQHashGenerator;

/**
 * MPQ hash table. Used to map file paths to block table indices.
 * <p>
 * Supports localised files using Windows Language ID codes. When requesting a
 * localised mapping it will prioritise finding the requested locale, then the
 * default locale and finally the first locale found.
 * <p>
 * File paths are uniquely identified using a combination of a 64 bit key and
 * their bucket position. As such the hash table does not know what file paths
 * it contains. To get around this limitation MPQs often contain a list file
 * which lists all the file paths used by the hash table. The list file can be
 * used to populate a different capacity hash table with the same mappings.
 */
public class HashTable {
    /**
     * Magic block number representing a hash table entry which is not in use.
     */
    private static final int ENTRY_UNUSED = -1;

    /**
     * Magic block number representing a hash table entry which was deleted.
     */
    private static final int ENTRY_DELETED = -2;

    /**
     * The default file locale, US English.
     */
    public static final short DEFAULT_LOCALE = 0;

    /**
     * Hash table bucket array.
     */
    private Bucket[] buckets;

    /**
     * The number of mappings in the hash table.
     */
    private int mappingNumber = 0;

    /**
     * Construct an empty hash table with the specified size.
     * <p>
     * The table can hold at most the specified capacity worth of file mappings,
     * which must be a power of 2.
     * 
     * @param capacity
     *            power of 2 capacity for the underlying bucket array.
     */
    public HashTable(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("Capacity must be power of 2.");
        }

        buckets = new Bucket[capacity];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket();
        }
    }

    public void readFromBuffer(ByteBuffer src) {
        for (int i = 0; i < buckets.length; i++) {
            Bucket entry = buckets[i];
            entry.readFromBuffer(src);

            // count active mappings
            final int blockIndex = entry.blockTableIndex;
            if (blockIndex != ENTRY_UNUSED && blockIndex != ENTRY_DELETED)
                mappingNumber++;
        }

    }

    public void writeToBuffer(ByteBuffer dest) {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i].writeToBuffer(dest);
        }
    }

    /**
     * Internal method to get a bucket index for the specified file.
     * 
     * @param file
     *            file identifier.
     * @return the bucket index used, or -1 if the file has no mapping.
     */
    private int getFileEntryIndex(FileIdentifier file) {
        final int mask = buckets.length - 1;
        final int start = file.offset & mask;
        int bestEntryIndex = -1;
        for (int c = 0; c < buckets.length; c++) {
            final int index = start + c & mask;
            final Bucket entry = buckets[index];

            if (entry.blockTableIndex == ENTRY_UNUSED) {
                break;
            } else if (entry.blockTableIndex == ENTRY_DELETED) {
                continue;
            } else if (entry.key == file.key) {
                if (entry.locale == file.locale) {
                    return index;
                } else if (bestEntryIndex == -1 || entry.locale == DEFAULT_LOCALE) {
                    bestEntryIndex = index;
                }
            }
        }

        return bestEntryIndex;
    }

    /**
     * Internal method to get a bucket for the specified file.
     * 
     * @param file
     *            file identifier.
     * @return the file bucket, or null if the file has no mapping.
     */
    private Bucket getFileEntry(FileIdentifier file) {
        final int index = getFileEntryIndex(file);
        return index != -1 ? buckets[index] : null;
    }

    /**
     * Get the block table index for the specified file.
     * 
     * @param name
     *            file path name.
     * @return block table index.
     * @throws IOException
     *             if the specified file has no mapping.
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
     * @param name
     *            file path name.
     * @param locale
     *            file locale.
     * @return block table index.
     * @throws IOException
     *             if the specified file has no mapping.
     */
    public int getFileBlockIndex(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);
        Bucket entry = getFileEntry(fid);

        if (entry == null)
            throw new JMpqException("File Not Found <" + name + ">.");
        else if (entry.blockTableIndex < 0)
            throw new JMpqException("File has invalid block table index <" + entry.blockTableIndex + ">.");

        return entry.blockTableIndex;
    }

    /**
     * Set a block table index for the specified file. Existing mappings are
     * updated.
     * 
     * @param name
     *            file path name.
     * @param locale
     *            file locale.
     * @param blockIndex
     *            block table index.
     * @throws IOException
     *             if the mapping cannot be created.
     */
    public void setFileBlockIndex(String name, short locale, int blockIndex) throws IOException {
        if (blockIndex < 0)
            throw new IllegalArgumentException("Block index numbers cannot be negative.");

        final FileIdentifier fid = new FileIdentifier(name, locale);

        // check if file entry already exists
        final Bucket exist = getFileEntry(fid);
        if (exist != null && exist.locale == locale) {
            exist.blockTableIndex = blockIndex;
            return;
        }

        // check if space for new entry
        if (mappingNumber == buckets.length)
            throw new JMpqException("Hash table cannot fit another mapping.");

        // locate suitable entry
        final int mask = buckets.length - 1;
        final int start = fid.offset & mask;
        Bucket newEntry = null;
        for (int c = 0; c < buckets.length; c++) {
            final Bucket entry = buckets[start + c & mask];

            if (entry.blockTableIndex == ENTRY_UNUSED || entry.blockTableIndex == ENTRY_DELETED) {
                newEntry = entry;
                break;
            }
        }

        // setup entry
        newEntry.key = fid.key;
        newEntry.locale = fid.locale;
        newEntry.blockTableIndex = blockIndex;
        mappingNumber++;
    }

    /**
     * Internal method to remove a file entry at the specified bucket index.
     * 
     * @param index
     *            bucket to clear.
     */
    private void removeFileEntry(int index) {
        final int bi = buckets[index].blockTableIndex;
        if (bi == ENTRY_UNUSED || bi == ENTRY_DELETED)
            throw new IllegalArgumentException("Bucket already clear.");

        // delete file
        final Bucket newEntry = new Bucket();
        newEntry.blockTableIndex = ENTRY_DELETED;
        buckets[index] = newEntry;
        mappingNumber--;

        // cleanup to empty if possible
        final int mask = buckets.length - 1;
        if (buckets[index + 1 & mask].blockTableIndex == ENTRY_UNUSED) {
            Bucket entry;
            int i = index;
            while ((entry = buckets[i]).blockTableIndex == ENTRY_DELETED) {
                entry.blockTableIndex = ENTRY_UNUSED;
                i = i - 1 & mask;
            }
        }
    }

    /**
     * Remove the specified file from the hash table.
     * 
     * @param name
     *            file path name.
     * @param locale
     *            file locale.
     * @throws IOException
     *             if the file cannot be found.
     */
    public void removeFile(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);

        // check if file exists
        final int index = getFileEntryIndex(fid);
        if (index == -1 || buckets[index].locale != locale)
            throw new JMpqException("File Not Found <" + name + ">");

        // delete file
        removeFileEntry(index);
    }

    /**
     * Remove the specified file from the hash table for all locales.
     * 
     * @param name
     *            file path name.
     * @return number of file entries that were removed.
     * @throws IOException
     *             if no file entries were found.
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
        if (count == 0)
            throw new JMpqException("File Not Found <" + name + ">");

        return count;
    }

    /**
     * Plain old data class to internally represent a uniquely identifiable
     * file.
     * <p>
     * Used to cache file name hash results.
     */
    private static class FileIdentifier {
        /**
         * 64 bit file key.
         */
        private final long key;

        /**
         * Offset into hash table bucket array to start search.
         */
        private final int offset;

        /**
         * File locale in the form of a Windows Language ID.
         */
        private final short locale;

        public FileIdentifier(final String name, final short locale) {
            // generate file offset
            final MPQHashGenerator offsetGen = MPQHashGenerator.getTableOffsetGenerator();
            offsetGen.process(name);
            offset = offsetGen.getHash();

            // generate file key
            final MPQHashGenerator key1Gen = MPQHashGenerator.getTableKey1Generator();
            key1Gen.process(name);
            final int key1 = key1Gen.getHash();
            final MPQHashGenerator key2Gen = MPQHashGenerator.getTableKey2Generator();
            key2Gen.process(name);
            final int key2 = key2Gen.getHash();
            key = (key2 << 32) | Integer.toUnsignedLong(key1);

            this.locale = locale;
        }
    }

    /**
     * Plain old data class for hash table buckets.
     */
    private static class Bucket {
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

        public Bucket() {
        }

        public void readFromBuffer(ByteBuffer src) {
            src.order(ByteOrder.LITTLE_ENDIAN);
            key = src.getLong();
            locale = src.getShort();
            src.getShort(); // platform not used
            blockTableIndex = src.getInt();
        }

        public void writeToBuffer(ByteBuffer dest) {
            dest.order(ByteOrder.LITTLE_ENDIAN);
            dest.putLong(key);
            dest.putShort((short) locale);
            dest.putShort((short) 0); // platform not used
            dest.putInt(blockTableIndex);
        }

        public String toString() {
            return "Entry [key=" + key + ",\tlcLocale=" + this.locale + ",\tdwBlockIndex=" + this.blockTableIndex + "]";
        }
    }
}