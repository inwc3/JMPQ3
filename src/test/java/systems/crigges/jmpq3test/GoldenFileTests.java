package systems.crigges.jmpq3test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Golden-file suite (P4-1): the safety net that has to be in place before the
 * core is restructured.
 * <p>
 * Three layers, each catching a different class of failure:
 * <ol>
 * <li><b>Read fidelity against an independent oracle.</b> Every file the
 * reference implementation could decode is extracted with JMPQ3 and compared
 * byte for byte. A disagreement means one of the two is wrong about the format,
 * which a self-referential test could never surface.</li>
 * <li><b>Round-trip content preservation.</b> Rebuild each archive, reopen it,
 * and require the same content. Run for both the copy path and the recompress
 * path, since they encode files differently.</li>
 * <li><b>Structural invariants of what we write.</b> The header must describe
 * the file that was actually produced.</li>
 * </ol>
 * A fourth layer lives outside Gradle: {@link #exportRebuiltArchivesForReferenceCheck()}
 * writes rebuilt archives and their expected digests to {@code build/roundtrip}
 * so {@code tools/mpqref.py verify} can confirm that something other than JMPQ3
 * can read them. CI runs that step; see {@code .github/workflows/build.yml}.
 */
public class GoldenFileTests {
    private static final Logger log = LoggerFactory.getLogger(GoldenFileTests.class);

    /**
     * Internal files a rebuild is known to drop today. {@code (attributes)}
     * regeneration is P2-4 and signing is out of scope, so both disappear.
     * Pinned deliberately: when P2-4 lands, this set must shrink and this test
     * must be updated to say so.
     */
    private static final Set<String> DROPPED_BY_REBUILD = Set.of("(attributes)", "(signature)");

    /**
     * Internal files a rebuild regenerates rather than copies, so their bytes
     * are expected to differ afterwards.
     */
    private static final Set<String> REGENERATED = Set.of("(listfile)");

    private GoldenManifest manifest;

    @BeforeClass
    public void loadManifest() {
        manifest = new GoldenManifest("golden/fixtures.tsv");
        log.info("golden manifest: {} entries across {} archives",
            manifest.size(), manifest.byArchive().size());
    }

    /**
     * Layer 1: JMPQ3's extraction must agree with the independent reference on
     * every file the reference could decode.
     */
    @Test
    public void extractionMatchesIndependentReference() throws IOException {
        int compared = 0;
        final List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, List<GoldenManifest.Entry>> archive : manifest.byArchive().entrySet()) {
            final Path mpq = TestResources.mpqCopy(archive.getKey());
            try (JMpqEditor editor = open(mpq, true)) {
                for (GoldenManifest.Entry expected : archive.getValue()) {
                    if (!expected.hasDigest()) {
                        continue;
                    }
                    if (!editor.hasFile(expected.name())) {
                        mismatches.add(archive.getKey() + ": JMPQ3 cannot find " + expected.name()
                            + " (flags " + expected.flags() + ")");
                        continue;
                    }
                    final byte[] actual = editor.extractFileAsBytes(expected.name());
                    if (actual.length != expected.size()) {
                        mismatches.add(archive.getKey() + ": " + expected.name()
                            + " size " + actual.length + ", reference says " + expected.size());
                    } else if (!TestHelper.md5(actual).equals(expected.md5())) {
                        mismatches.add(archive.getKey() + ": " + expected.name()
                            + " content differs from the reference (codec " + expected.codec() + ")");
                    } else {
                        compared++;
                    }
                }
            }
        }

        assertNoProblems(mismatches, "extraction disagrees with the reference");
        // Guard against the suite silently degrading to comparing nothing.
        Assert.assertTrue(compared >= 150, "only compared " + compared + " files against the reference");
        log.info("verified {} files against the independent reference", compared);
    }

    /**
     * Layer 1b: JMPQ3 must also find every file the reference located, even
     * those whose codec the reference cannot decode.
     */
    @Test
    public void everyReferenceFileIsVisibleToJmpq() throws IOException {
        final List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<GoldenManifest.Entry>> archive : manifest.byArchive().entrySet()) {
            final Path mpq = TestResources.mpqCopy(archive.getKey());
            try (JMpqEditor editor = open(mpq, true)) {
                for (GoldenManifest.Entry expected : archive.getValue()) {
                    if (!editor.hasFile(expected.name())) {
                        missing.add(archive.getKey() + ": " + expected.name());
                    }
                }
            }
        }
        assertNoProblems(missing, "files the reference found but JMPQ3 cannot see");
    }

    /**
     * Layer 2: a rebuild must preserve content. Uses the copy path
     * ({@code recompress = false}).
     */
    @Test
    public void rebuildPreservesContent() throws IOException {
        assertRoundTrip(new RecompressOptions(false), "copy");
    }

    /**
     * Layer 2: the same, through the recompress path, which decodes and
     * re-encodes every file instead of copying its stored bytes.
     */
    @Test
    public void recompressPreservesContent() throws IOException {
        final RecompressOptions options = new RecompressOptions(true);
        options.newSectorSizeShift = 15;
        assertRoundTrip(options, "recompress");
    }

    private void assertRoundTrip(RecompressOptions options, String label) throws IOException {
        final List<String> problems = new ArrayList<>();

        for (Map.Entry<String, List<GoldenManifest.Entry>> archive : manifest.byArchive().entrySet()) {
            final String name = archive.getKey();
            final Path mpq = TestResources.mpqCopy(name);

            // What JMPQ3 sees before the rebuild, so files the reference cannot
            // decode are still covered.
            final Map<String, String> before = digestEverything(mpq);
            if (before.isEmpty()) {
                // Nothing this archive can enumerate; the read tests cover it.
                continue;
            }

            try (JMpqEditor editor = open(mpq, false)) {
                if (!editor.isCanWrite()) {
                    continue;
                }
                editor.close(true, false, options);
            }

            final Map<String, String> after = digestEverything(mpq);
            for (Map.Entry<String, String> expected : before.entrySet()) {
                if (DROPPED_BY_REBUILD.contains(expected.getKey()) || REGENERATED.contains(expected.getKey())) {
                    continue;
                }
                final String actual = after.get(expected.getKey());
                if (actual == null) {
                    problems.add(name + " [" + label + "]: lost " + expected.getKey());
                } else if (!actual.equals(expected.getValue())) {
                    problems.add(name + " [" + label + "]: " + expected.getKey() + " content changed");
                }
            }

            // The list file is regenerated rather than copied, so its bytes are
            // expected to change: entries naming files the archive does not
            // actually hold are discarded (listfileTooLong.w3x exists for
            // exactly that). What must hold is that it still exists and that
            // every name in it resolves.
            if (!after.containsKey("(listfile)")) {
                problems.add(name + " [" + label + "]: rebuilt archive has no (listfile)");
            }
            try (JMpqEditor editor = open(mpq, true)) {
                for (String listed : editor.getFileNames()) {
                    if (!editor.hasFile(listed)) {
                        problems.add(name + " [" + label + "]: (listfile) names absent file " + listed);
                    }
                }
            }

            assertHeaderDescribesFile(mpq, name + " [" + label + "]", problems);
        }

        assertNoProblems(problems, "round trip through the " + label + " path lost data");
    }

    /**
     * Layer 2b: the internal files a rebuild drops must be exactly the set we
     * know about, so a new omission cannot slip in unnoticed.
     */
    @Test
    public void rebuildDropsOnlyKnownInternalFiles() throws IOException {
        final Path mpq = TestResources.mpqCopy("ydwe");
        final Set<String> before = new LinkedHashSet<>(digestEverything(mpq).keySet());

        try (JMpqEditor editor = open(mpq, false)) {
            Assert.assertTrue(editor.isCanWrite());
        }

        final Set<String> after = digestEverything(mpq).keySet();
        final Set<String> dropped = new LinkedHashSet<>(before);
        dropped.removeAll(after);

        Assert.assertEquals(dropped, DROPPED_BY_REBUILD,
            "the set of files lost by a rebuild changed");
    }

    /**
     * Layer 3: the header of an archive we wrote must describe the file that
     * exists on disk.
     */
    private void assertHeaderDescribesFile(Path mpq, String label, List<String> problems) throws IOException {
        final byte[] image = Files.readAllBytes(mpq);
        final ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);

        int headerOffset = -1;
        for (int pos = 0; pos + 4 <= image.length; pos += 0x200) {
            if (buffer.getInt(pos) == JMpqEditor.ARCHIVE_HEADER_MAGIC) {
                headerOffset = pos;
                break;
            }
        }
        if (headerOffset < 0) {
            problems.add(label + ": rebuilt archive has no header");
            return;
        }

        final long archiveSize = buffer.getInt(headerOffset + 8) & 0xFFFFFFFFL;
        final long hashPos = buffer.getInt(headerOffset + 16) & 0xFFFFFFFFL;
        final long blockPos = buffer.getInt(headerOffset + 20) & 0xFFFFFFFFL;
        final long hashCount = buffer.getInt(headerOffset + 24) & 0x0FFFFFFFL;
        final long blockCount = buffer.getInt(headerOffset + 28) & 0xFFFFFFFFL;

        if (headerOffset + archiveSize != image.length) {
            problems.add(label + ": header says the archive is " + archiveSize
                + " bytes from offset " + headerOffset + ", but the file is " + image.length);
        }
        if (headerOffset + hashPos + hashCount * 16 > image.length) {
            problems.add(label + ": hash table runs past the end of the file");
        }
        if (headerOffset + blockPos + blockCount * 16 > image.length) {
            problems.add(label + ": block table runs past the end of the file");
        }
        if (blockPos != hashPos + hashCount * 16) {
            problems.add(label + ": block table does not follow the hash table");
        }
    }

    /**
     * Exports rebuilt archives plus their expected content digests, for
     * {@code tools/mpqref.py verify} to check independently.
     * <p>
     * Only files JMPQ3 rewrote as zlib or stored sectors are listed, because
     * those are the only codecs the reference implements; that is exactly the
     * set JMPQ3's writer can emit, so coverage of the write path is complete.
     */
    @Test
    public void exportRebuiltArchivesForReferenceCheck() throws IOException {
        final Path out = Path.of("build", "roundtrip");
        final Path archives = out.resolve("archives");
        Files.createDirectories(archives);
        final Map<String, Map<String, String>> expected = new TreeMap<>();

        for (String name : manifest.byArchive().keySet()) {
            final Path source = TestResources.mpqCopy(name);
            final Map<String, String> before = digestEverything(source);
            if (before.isEmpty()) {
                continue;
            }

            try (JMpqEditor editor = open(source, false)) {
                if (!editor.isCanWrite()) {
                    continue;
                }
                // Recompress so every file is re-encoded as zlib or stored,
                // which is what the reference can verify.
                final RecompressOptions options = new RecompressOptions(true);
                editor.close(true, false, options);
            }

            final Map<String, String> kept = new TreeMap<>();
            for (Map.Entry<String, String> entry : before.entrySet()) {
                // Regenerated files legitimately differ after a rebuild, so
                // there is nothing for the reference to compare them against.
                if (!DROPPED_BY_REBUILD.contains(entry.getKey())
                    && !REGENERATED.contains(entry.getKey())) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            if (kept.isEmpty()) {
                // Nothing the reference could be asked to confirm, so exporting
                // it would only produce an unmatched archive.
                log.info("not exporting {}: no verifiable content after rebuild", name);
                continue;
            }
            Files.copy(source, archives.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            expected.put(name, kept);
        }

        Assert.assertFalse(expected.isEmpty(), "exported no archives for reference verification");
        writeExpectedManifest(out.resolve("expected.tsv"), expected);
        log.info("exported {} rebuilt archives to {} for independent verification",
            expected.size(), out.toAbsolutePath());
    }

    private void writeExpectedManifest(Path target, Map<String, Map<String, String>> expected) throws IOException {
        final StringBuilder text = new StringBuilder("# archive\tname\tsize\tmd5\n");
        for (Map.Entry<String, Map<String, String>> archive : expected.entrySet()) {
            for (Map.Entry<String, String> file : archive.getValue().entrySet()) {
                final String[] sizeAndDigest = file.getValue().split(":", 2);
                text.append(archive.getKey()).append('\t')
                    .append(file.getKey()).append('\t')
                    .append(sizeAndDigest[0]).append('\t')
                    .append(sizeAndDigest[1]).append('\n');
            }
        }
        Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * @return every name JMPQ3 can enumerate mapped to {@code size:md5} of its
     *         content, skipping files that cannot be decoded at all.
     */
    private Map<String, String> digestEverything(Path mpq) throws IOException {
        final Map<String, String> digests = new LinkedHashMap<>();
        try (JMpqEditor editor = open(mpq, true)) {
            final List<String> names = new ArrayList<>(editor.getFileNames());
            for (String internal : List.of("(listfile)", "(attributes)", "(signature)")) {
                if (editor.hasFile(internal) && !names.contains(internal)) {
                    names.add(internal);
                }
            }
            for (String name : names) {
                if (!editor.hasFile(name)) {
                    continue;
                }
                try {
                    final byte[] content = editor.extractFileAsBytes(name);
                    digests.put(name, content.length + ":" + TestHelper.md5(content));
                } catch (IOException e) {
                    log.debug("skipping undecodable {} in {}", name, mpq.getFileName(), e);
                }
            }
        }
        return digests;
    }

    private static void assertNoProblems(List<String> problems, String what) {
        if (!problems.isEmpty()) {
            Assert.fail(what + " (" + problems.size() + "):" + System.lineSeparator()
                + String.join(System.lineSeparator(), problems));
        }
    }

    /**
     * Opens a fixture the way the golden manifest was generated: forcing format
     * version 0, which is how Warcraft III itself reads these archives. Several
     * fixtures have deliberately corrupted headers and cannot be opened any
     * other way until the tolerant parsing of P2-5a lands.
     */
    private JMpqEditor open(Path mpq, boolean readOnly) throws IOException {
        final List<MPQOpenOption> options = new ArrayList<>();
        if (readOnly) {
            options.add(MPQOpenOption.READ_ONLY);
        }
        options.add(MPQOpenOption.FORCE_V0);
        return new JMpqEditor(mpq, options.toArray(new MPQOpenOption[0]));
    }
}
