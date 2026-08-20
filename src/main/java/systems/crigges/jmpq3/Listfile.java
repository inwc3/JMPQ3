package systems.crigges.jmpq3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedCollection;

/**
 * The contents of an archive's {@code (listfile)}: the names of the files it
 * holds.
 * <p>
 * MPQ hash tables store only hashes, so an archive cannot enumerate itself. The
 * list file is the convention that makes enumeration possible.
 *
 * <h2>What changed in 2.0</h2>
 * The previous implementation was a {@code HashMap<Long, String>} keyed on the
 * 64-bit MPQ file key. Three consequences, all fixed here:
 * <ul>
 * <li><b>Silent data loss.</b> Two names colliding on the 64-bit key made one
 * of them vanish with no diagnostic. Collisions are now reported.</li>
 * <li><b>Non-deterministic output.</b> {@code HashMap} iteration order decided
 * the order of {@code (listfile)} and, through it, the block table layout, so
 * rebuilding the same archive twice produced different bytes. Insertion order
 * is now preserved.</li>
 * <li><b>Platform dependent encoding.</b> {@code asByteArray} used
 * {@code String.getBytes()} with the platform default charset, so the same
 * archive built on two machines could differ. UTF-8 is now explicit.</li>
 * </ul>
 */
public class Listfile {
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());

    /**
     * Canonical name to display name. Insertion ordered so that
     * {@code (listfile)} output, and therefore the rebuilt block table, is
     * reproducible.
     */
    private final LinkedHashMap<String, String> files = new LinkedHashMap<>();

    /**
     * Parses a list file.
     *
     * @param file raw {@code (listfile)} content; interpreted as UTF-8.
     */
    public Listfile(byte[] file) {
        // Split on either line ending, and drop the empty trailing element a
        // final CRLF produces.
        for (String line : new String(file, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            addFile(line.strip());
        }
    }

    /**
     * Creates an empty list file.
     */
    public Listfile() {
    }

    /**
     * @return the file names in insertion order.
     */
    public SequencedCollection<String> getFiles() {
        return Collections.unmodifiableSequencedCollection(files.sequencedValues());
    }

    /**
     * @return live view keyed on canonical name; entries may be removed through
     *         it.
     */
    public Map<String, String> getFileMap() {
        return files;
    }

    /**
     * Adds a name. Already present names keep their original casing and
     * position.
     *
     * @param name file path; blank and {@code null} names are ignored.
     */
    public final void addFile(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        final String key = MpqNames.canonical(name);
        final String display = MpqNames.display(name);
        final String previous = files.putIfAbsent(key, display);
        if (previous != null && !previous.equals(display)) {
            // Same file, different spelling. Harmless, but worth knowing about
            // when diffing archives.
            log.debug("Listfile already contains {} as {}; keeping the first spelling.", display, previous);
        }
    }

    /**
     * @param name file path.
     */
    public void removeFile(String name) {
        files.remove(MpqNames.canonical(name));
    }

    /**
     * @param name file path.
     * @return whether the list file names this file, comparing case
     *         insensitively and separator insensitively.
     */
    public boolean containsFile(String name) {
        return files.containsKey(MpqNames.canonical(name));
    }

    /**
     * @return number of distinct files named.
     */
    public int size() {
        return files.size();
    }

    /**
     * Serialises to {@code (listfile)} form: CRLF separated, UTF-8 encoded, in
     * insertion order.
     *
     * @return the bytes to store as {@code (listfile)}.
     */
    public byte[] asByteArray() {
        final StringBuilder out = new StringBuilder(files.size() * 32);
        for (String entry : files.values()) {
            out.append(entry).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reports names that collide on the 64-bit MPQ file key.
     * <p>
     * Such names are indistinguishable to the hash table, so an archive cannot
     * hold both. This never happens by accident, but protected archives use it
     * deliberately.
     *
     * @return colliding display names, grouped by the key they share; empty
     *         when the list file is sound.
     */
    public Collection<Collection<String>> findKeyCollisions() {
        final Map<Long, Collection<String>> byKey = new LinkedHashMap<>();
        for (String name : files.values()) {
            byKey.computeIfAbsent(MpqNames.fileKey(name), k -> new java.util.ArrayList<>()).add(name);
        }
        return byKey.values().stream().<Collection<String>>map(g -> g).filter(group -> group.size() > 1).toList();
    }
}
