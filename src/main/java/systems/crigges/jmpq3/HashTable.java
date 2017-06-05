package systems.crigges.jmpq3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import systems.crigges.jmpq3.security.MPQHashGenerator;

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
     * Table bucket array.
     */
    private Entry[] entries;
    
    /**
     * The number of mappings in the hash table.
     */
    private int mappingNumber = 0;
    
    /**
     * Construct an empty file name lookup table with the specified size.
     * <p>
     * The table can hold at most the specified size worth of file name mappings.
     * <p>
     * The table must have a size that is a power of 2.
     * 
     * @param size power of 2 size of the underlying table.
     */
    public HashTable(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("Parameter 'size' must be power of 2.");
        }
        
        entries = new Entry[size];
        for (int i = 0 ; i < entries.length ; i++) {
            entries[i] = new Entry();
        }
    }
    
    public void readFromBuffer(ByteBuffer src) {
        for (int i = 0 ; i < entries.length ; i++) {
            Entry entry = entries[i];
            entry.readFromBuffer(src);
            
            // count active mappings
            final int blockIndex = entry.dwBlockIndex;
            if (blockIndex != ENTRY_UNUSED || blockIndex != ENTRY_DELETED) mappingNumber++;
        }
        
        
    }

    public void writeToBuffer(ByteBuffer dest) {
        for (int i = 0 ; i < entries.length ; i++) {
            entries[i].writeToBuffer(dest);
        }
    }
    
    private int getFileEntryIndex(FileIdentifier file) {        
        final int mask = entries.length - 1;
        final int start = file.offset & mask;
        int bestEntryIndex = -1;
        for (int c = 0; c < entries.length; c++) {
            final int index = start + c & mask;
            final Entry entry = entries[index];
            
            if (entry.dwBlockIndex == ENTRY_UNUSED){
                break;
            } else if (entry.dwBlockIndex == ENTRY_DELETED) {
                continue;
            } else if (entry.key == file.key && (bestEntryIndex == -1 || entry.lcLocale == file.locale || entry.lcLocale != file.locale && entry.lcLocale == DEFAULT_LOCALE)) {
                bestEntryIndex = index;
            }
        }
        
        return bestEntryIndex;
    }
    
    private Entry getFileEntry(FileIdentifier file) {        
        final int index = getFileEntryIndex(file);
        return index != -1 ? entries[index] : null;
    }
    
    public int getBlockIndexOfFile(String name) throws IOException {
        return getFileBlockIndex(name, DEFAULT_LOCALE);
    }

    public int getFileBlockIndex(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);
        Entry entry = getFileEntry(fid);
        
        if (entry == null) throw new JMpqException("File Not Found <" + name + ">");
        
        return entry.dwBlockIndex;
    }
    
    public void setFileBlockIndex(String name, short locale, int blockIndex) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);
        
        // check if file entry already exists
        final Entry exist = getFileEntry(fid);
        if (exist != null && exist.lcLocale == locale) {
            exist.dwBlockIndex = blockIndex;
            return;
        }
        
        // check if space for new entry
        if (mappingNumber == entries.length) throw new JMpqException("Hash table cannot fit another mapping.");
        
        // locate suitable entry
        final int mask = entries.length - 1;
        final int start = fid.offset & mask;
        Entry newEntry = null;
        for (int c = 0; c < entries.length; c++) {
            final Entry entry = entries[start + c & mask];
            
            if (entry.dwBlockIndex == ENTRY_UNUSED || entry.dwBlockIndex == ENTRY_DELETED){
                newEntry = entry;
                break;
            }
        }
        
        // setup entry
        newEntry.key = fid.key;
        newEntry.lcLocale = fid.locale;
        newEntry.dwBlockIndex = blockIndex;
        mappingNumber++;
    }
    
    private void removeFileEntry(int index) {
        // delete file
        final Entry newEntry = new Entry();
        newEntry.dwBlockIndex = ENTRY_DELETED;
        entries[index] = newEntry;
        mappingNumber--;
        
        // cleanup to empty if possible
        final int mask = entries.length - 1;
        if (entries[index + 1 & mask].dwBlockIndex == ENTRY_UNUSED) {
            Entry entry;
            int i = index;
            while((entry = entries[i]).dwBlockIndex == ENTRY_DELETED) {
                entry.dwBlockIndex = ENTRY_UNUSED;
                i = i - 1 & mask;
            }
        }
    }
    
    public void removeFile(String name, short locale) throws IOException {
        final FileIdentifier fid = new FileIdentifier(name, locale);
        
        // check if file exists
        final int index = getFileEntryIndex(fid);
        if (index == -1 || entries[index].lcLocale != locale) throw new JMpqException("File Not Found <" + name + ">");
        
        // delete file
        removeFileEntry(index);
    }
    
    public int removeFileAll(String name) throws IOException {
        int count = 0;
        int index;
        final FileIdentifier fid = new FileIdentifier(name, DEFAULT_LOCALE);
        while ((index = getFileEntryIndex(fid)) != -1) {
            removeFileEntry(index);
            count++;
        }
        
        // check if file was removed
        if (count == 0) throw new JMpqException("File Not Found <" + name + ">");
        
        return count;
    }
    
    private static class FileIdentifier {
        private final long key;
        private final int offset;
        private final short locale;
        
        public FileIdentifier(final String name, final short locale) {
            final MPQHashGenerator offsetGen = MPQHashGenerator.getTableOffsetGenerator();
            offsetGen.process(name);
            offset = offsetGen.getHash();
            
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

    private static class Entry {
        private long key = 0;

        private short lcLocale = 0;
        private int dwBlockIndex = ENTRY_UNUSED;

        public Entry() {
        }

        public void readFromBuffer(ByteBuffer src) {
            src.order(ByteOrder.LITTLE_ENDIAN);
            key = src.getLong();
            lcLocale = src.getShort();
            src.getShort(); // platform not used
            dwBlockIndex = src.getInt();
        }

        public void writeToBuffer(ByteBuffer dest) {
            dest.order(ByteOrder.LITTLE_ENDIAN);
            dest.putLong(key);
            dest.putShort((short) lcLocale);
            dest.putShort((short) 0); // platform not used
            dest.putInt(dwBlockIndex);
        }

        public String toString() {
            return "Entry [key=" + key + ",\tlcLocale=" + this.lcLocale + ",\tdwBlockIndex=" + this.dwBlockIndex
                    + "]";
        }
    }
}