package systems.crigges.jmpq3;

import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.util.Locale;

/**
 * The single place that decides what an MPQ file path <em>is</em>.
 * <p>
 * MPQ paths are case insensitive, which follows directly from how the format
 * hashes them: every hash generator upper-cases its input, so
 * {@code "Units\Test.txt"} and {@code "units\test.txt"} are the same file.
 * <p>
 * Before this class existed the library compared raw strings in some places
 * (the pending-insert map) and MPQ hash keys in others (the list file), so
 * {@code "Test"} and {@code "teST"} could disagree about whether a file
 * existed. Everything that keys, looks up, or deduplicates a file path now goes
 * through {@link #canonical(String)}.
 *
 * <h2>Separators are not interchangeable</h2>
 * {@code '\'} is the MPQ path separator, and {@code '/'} is an ordinary
 * character that hashes differently. A file stored as {@code "dir/file"} is a
 * different file from {@code "dir\file"} and neither can be found by the
 * other's name.
 * <p>
 * This mirrors StormLib exactly, which is subtler than it looks. StormLib has
 * two hash functions: {@code HashString} folds {@code '/'} to {@code '\'},
 * while {@code HashStringSlash} deliberately does not
 * ("DON'T convert slash (0x2F) to backslash (0x5C)"). Archives opened through
 * {@code OpenArchiveFromStream} get {@code pfnHashString = HashStringSlash}, so
 * every hash table lookup uses the <em>non</em>-folding variant. Folding here
 * would silently rename any archive entry containing a forward slash: the
 * renamed entry no longer resolves, so a writable rebuild would drop the file
 * as a stale list file entry.
 * <p>
 * The one place StormLib does fold is the file encryption key
 * ({@code DecryptFileKey} calls {@code HashString}), and it does so after
 * stripping the directory part, where a separator can no longer appear. See
 * {@link #baseFileKey(String)}.
 *
 * <h2>Case folding</h2>
 * Folding uses {@link Locale#ROOT} deliberately. {@link String#toUpperCase()}
 * with a Turkish default locale maps {@code 'i'} to {@code 'İ'} (U+0130), which
 * would hash differently from the {@code 'I'} every other MPQ implementation
 * produces and make archives written on such a machine unreadable elsewhere.
 */
public final class MpqNames {
    private MpqNames() {
    }

    /**
     * Normalises a path to the form used for hashing, keying and comparison.
     * <p>
     * Case only. Separators are left exactly as given, because they are part of
     * the name's identity; see the class documentation.
     *
     * @param name file path as supplied by a caller.
     * @return the canonical form: upper case, separators untouched.
     * @throws NullPointerException if {@code name} is {@code null}.
     */
    public static String canonical(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    /**
     * The 64-bit content key MPQ uses to identify a path independently of the
     * hash table's capacity.
     *
     * @param name file path; case folded internally.
     * @return the combined key1/key2 pair.
     */
    public static long fileKey(String name) {
        final String canonical = canonical(name);
        final MPQHashGenerator key1Gen = MPQHashGenerator.getTableKey1Generator();
        key1Gen.process(canonical);
        final MPQHashGenerator key2Gen = MPQHashGenerator.getTableKey2Generator();
        key2Gen.process(canonical);
        return ((long) key2Gen.getHash() << 32) | Integer.toUnsignedLong(key1Gen.getHash());
    }

    /**
     * The hash used to pick a starting bucket in the hash table.
     *
     * @param name file path; case folded internally.
     * @return the bucket offset hash.
     */
    public static int tableOffset(String name) {
        final MPQHashGenerator gen = MPQHashGenerator.getTableOffsetGenerator();
        gen.process(canonical(name));
        return gen.getHash();
    }

    /**
     * The base encryption key for a file's sector data.
     * <p>
     * MPQ derives it from the <em>file name only</em>, with the directory part
     * stripped, which is why two files with the same name in different
     * directories share a base key. Stripping happens at the last {@code '\'}
     * <em>or</em> {@code '/'}, matching StormLib's {@code GetPlainFileName},
     * which treats both as separators for this purpose even though only
     * {@code '\'} is significant to the hash table.
     *
     * @param name full file path.
     * @return the base key, before any {@code ADJUSTED_ENCRYPTED} offsetting.
     */
    public static int baseFileKey(String name) {
        final String canonical = canonical(name);
        final int separator = Math.max(canonical.lastIndexOf('\\'), canonical.lastIndexOf('/'));
        final String pathless = canonical.substring(separator + 1);
        final MPQHashGenerator gen = MPQHashGenerator.getFileKeyGenerator();
        gen.process(pathless);
        return gen.getHash();
    }

    /**
     * Applies the {@code MPQ_FILE_KEY_V2} ("adjusted") transform to a base key.
     * <p>
     * The adjustment folds the file's position in the archive and its
     * uncompressed size into the key, so moving a file changes its encryption
     * key. That is the reason a rebuild cannot simply copy encrypted sectors to
     * a new offset.
     *
     * @param baseKey    key from {@link #baseFileKey(String)}.
     * @param filePos    file position relative to the archive header.
     * @param normalSize uncompressed file size.
     * @return the adjusted key.
     */
    public static int adjustKey(int baseKey, long filePos, int normalSize) {
        return (int) ((baseKey + filePos) ^ normalSize);
    }

    /**
     * Resolves the sector encryption key for a file, applying the adjusted
     * transform when the flags call for it.
     *
     * @param name       full file path.
     * @param flags      block flags.
     * @param filePos    file position relative to the archive header.
     * @param normalSize uncompressed file size.
     * @return the sector base key, or 0 when the file is not encrypted.
     */
    public static int sectorKey(String name, int flags, long filePos, int normalSize) {
        if ((flags & MpqFile.ENCRYPTED) != MpqFile.ENCRYPTED) {
            return 0;
        }
        final int baseKey = baseFileKey(name);
        if ((flags & MpqFile.ADJUSTED_ENCRYPTED) == MpqFile.ADJUSTED_ENCRYPTED) {
            return adjustKey(baseKey, filePos, normalSize);
        }
        return baseKey;
    }
}
