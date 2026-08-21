package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;

import java.io.IOException;
import java.nio.channels.NonWritableChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The edge-case contracts of P1-7, pinned so they cannot drift silently.
 * <p>
 * Each of these was previously a log line and a shrug: the library would warn
 * and carry on, and a caller had no way to find out what had happened. They are
 * now observable facts, and this class is the specification of them.
 */
public class BehaviourContractTests {

    // ------------------------------------------------- archives with no listfile

    /**
     * An archive with no {@code (listfile)} cannot enumerate itself, because the
     * hash table stores hashes rather than names. Its files stay readable by
     * exact name.
     */
    @Test
    public void archiveWithoutListfileIsNotEnumerableButIsReadable() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.isEnumerable());
            Assert.assertTrue(archive.names().isEmpty());

            // But the blocks are there, and readable by exact name.
            Assert.assertTrue(archive.blockCount() > 0);
            Assert.assertTrue(archive.contains("war3map.j"), "readable by exact name");
            Assert.assertTrue(archive.read("war3map.j").length > 0);
        }
    }

    /**
     * The facade downgrades such an archive to read-only rather than rebuilding
     * it and dropping every file it cannot name.
     */
    @Test
    public void facadeDowngradesUnenumerableArchiveToReadOnly() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.isCanWrite(), "must not offer to rebuild what it cannot enumerate");
            Assert.expectThrows(NonWritableChannelException.class,
                () -> editor.insertByteArray("x.txt", new byte[1]));
        }
    }

    /** An empty list file is still a list file: such an archive is writable. */
    @Test
    public void emptyListfileStillAllowsWriting() throws IOException {
        Path archivePath = TestResources.scratchDir("empty-listfile").resolve("fresh.w3x");
        JMpqEditor.createEmptyArchive(archivePath.toFile());

        try (JMpqEditor editor = new JMpqEditor(archivePath, MPQOpenOption.FORCE_V0)) {
            Assert.assertTrue(editor.isCanWrite(),
                "a fresh archive must be able to receive its first file");
            editor.insertByteArray("first.txt", "hello".getBytes(StandardCharsets.UTF_8));
        }
        try (JMpqEditor editor = new JMpqEditor(archivePath, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            Assert.assertEquals(new String(editor.extractFileAsBytes("first.txt"), StandardCharsets.UTF_8),
                "hello");
        }
    }

    // ------------------------------------------------ incomplete listfiles

    /**
     * A rebuild can only carry over files it can name, so an incomplete list
     * file means data loss. The count is queryable in advance rather than
     * discovered afterwards.
     */
    @Test
    public void incompleteListfileReportsWhatARebuildWouldDrop() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            // Nothing is named, so every live block would be dropped.
            Assert.assertEquals(archive.filesLostOnRebuild(), archive.blockCount());
        }

        // A complete list file drops nothing.
        Path complete = TestResources.mpqCopy("normalMap");
        try (MpqArchive archive = MpqArchive.open(complete, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.isEnumerable());
            // normalMap holds an (attributes) file, which a rebuild does not
            // carry over, so exactly one file is at risk.
            Assert.assertEquals(archive.filesLostOnRebuild(), 1,
                "only the (attributes) file should be at risk");
            Assert.assertEquals(archive.unnamedBlockCount(), 0,
                "every block should be nameable");
        }
    }

    /**
     * An external list file recovers an unenumerable archive, and the recovered
     * files survive the rebuild.
     */
    @Test
    public void externalListfileRecoversAnUnenumerableArchive() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        Path listfile = TestResources.file("listfile.txt");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.isCanWrite());
            editor.setExternalListfile(listfile.toFile());
            Assert.assertTrue(editor.isCanWrite(), "an external listfile must restore writability");
            editor.insertByteArray("recovered.txt", "yes".getBytes(StandardCharsets.UTF_8));
        }

        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.isEnumerable(), "the rebuild should have written a listfile");
            Assert.assertTrue(archive.contains("recovered.txt"));
            Assert.assertTrue(archive.names().size() > 1,
                "the recovered names should be in the rebuilt listfile: " + archive.names());
        }
    }

    /** A READ_ONLY editor stays read-only whatever list file it is handed. */
    @Test
    public void readOnlyIsNeverOverridden() throws IOException {
        Path mpq = TestResources.mpqCopy("listfilelessMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            editor.setExternalListfile(TestResources.file("listfile.txt").toFile());
            Assert.assertFalse(editor.isCanWrite());
        }
    }

    // -------------------------------------------------------- header prefix

    /**
     * Warcraft III maps carry bytes before the archive header. Dropping them
     * stops the map loading, so preserving them is the default and dropping
     * them is explicit.
     */
    @Test
    public void headerPrefixIsPreservedByDefaultAndDroppableOnRequest() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        final long prefix;
        final byte[] kept;
        final byte[] dropped;

        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            prefix = archive.header().headerOffset();
            Assert.assertTrue(prefix > 0, "fixture should have a prefix");
            kept = MpqArchiveWriter.from(archive, MpqWriteOptions.defaults()).toByteArray();
            dropped = MpqArchiveWriter
                .from(archive, MpqWriteOptions.defaults().withPrefix(false)).toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(kept, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().headerOffset(), prefix);
        }
        try (MpqArchive archive = MpqArchive.open(dropped, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().headerOffset(), 0);
        }
    }

    /** The facade's setKeepHeaderOffset(false) moves the archive to offset 0. */
    @Test
    public void facadeCanDropTheHeaderPrefix() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.setKeepHeaderOffset(false);
        }
        final byte[] image = Files.readAllBytes(mpq);
        final int magic = java.nio.ByteBuffer.wrap(image)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0);
        Assert.assertEquals(magic, JMpqEditor.ARCHIVE_HEADER_MAGIC,
            "the archive should now start at offset 0");
    }

    // ------------------------------------------------------ in-memory archives

    /** An in-memory archive never writes back to the caller's array. */
    @Test
    public void inMemoryArchiveLeavesTheCallersArrayAlone() throws IOException {
        final byte[] caller = Files.readAllBytes(TestResources.mpqCopy("normalMap"));
        final byte[] pristine = caller.clone();

        JMpqEditor editor = new JMpqEditor(caller, MPQOpenOption.FORCE_V0);
        editor.insertByteArray("added.txt", "x".getBytes(StandardCharsets.UTF_8));
        editor.close();

        Assert.assertEquals(caller, pristine, "the rebuild wrote into the caller's array");
        Assert.assertNotNull(editor.getOutputByteArray(), "the rebuilt image must be retrievable");

        try (MpqArchive rebuilt =
                 MpqArchive.open(editor.getOutputByteArray(), MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(rebuilt.contains("added.txt"));
        }
    }

    /** A read-only in-memory archive produces no image, having rebuilt nothing. */
    @Test
    public void readOnlyInMemoryArchiveProducesNoImage() throws IOException {
        final byte[] caller = Files.readAllBytes(TestResources.mpqCopy("normalMap"));
        JMpqEditor editor = new JMpqEditor(caller, MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0);
        Assert.assertFalse(editor.getFileNames().isEmpty());
        editor.close();
        Assert.assertNull(editor.getOutputByteArray());
    }

    // ------------------------------------------------------- explicit failures

    /** Reading an absent file fails rather than returning nothing. */
    @Test
    public void absentFileFailsExplicitly() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.contains("nope.txt"));
            Assert.assertTrue(archive.entry("nope.txt").isEmpty());
            Assert.expectThrows(IOException.class, () -> archive.read("nope.txt"));
        }
    }

    /** Closing twice is harmless, so try-with-resources plus an explicit close is safe. */
    @Test
    public void closingTwiceIsHarmless() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("a.txt", new byte[]{1});
            editor.close();
            editor.close();
        }
        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.contains("a.txt"));
        }
    }

    /**
     * The facade must not accept an edit it cannot persist. 1.x accepted a
     * caller-supplied {@code (listfile)} and then wrote an archive with two
     * entries under one name; failing at insertion, while the caller can still
     * react, is the lesser evil.
     */
    @Test
    public void facadeRejectsAnInsertItCannotPersist() throws IOException {
        Path mpq = TestResources.mpqCopy("normalMap");
        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.expectThrows(IllegalArgumentException.class,
                () -> editor.insertByteArray("(listfile)", new byte[1]));

            // The other internal files are not generated, so they are accepted
            // and must survive the rebuild.
            editor.insertByteArray("(attributes)", new byte[]{100, 0, 0, 0, 3, 0, 0, 0}, true);
        }
        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(archive.contains("(attributes)"),
                "an explicitly supplied (attributes) file should be written");
        }
    }

    /**
     * The 1.x API has no locale parameter, so an override replaces the path as a
     * whole. Putting only the neutral variant would leave other locales behind
     * holding stale content.
     */
    @Test
    public void legacyOverrideReplacesEveryLocaleVariant() throws IOException {
        final short german = 0x407;
        final byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("war3map.wts", (short) 0, "old-neutral".getBytes(StandardCharsets.UTF_8))
            .put("war3map.wts", german, "old-german".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        Path mpq = TestResources.scratchDir("locale-override").resolve("m.w3x");
        Files.write(mpq, image);

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            editor.insertByteArray("war3map.wts", "new".getBytes(StandardCharsets.UTF_8), true);
        }

        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.localesOf("war3map.wts").size(), 1,
                "the override should have replaced the path, not one variant");
            Assert.assertEquals(new String(archive.read("war3map.wts"), StandardCharsets.UTF_8),
                "new");
        }
    }

    /**
     * A writable in-memory editor must not alias the caller's array: it stays
     * open across many calls and rebuilds at close, so a later mutation would
     * change the live archive underneath it.
     */
    @Test
    public void writableInMemoryEditorDoesNotAliasTheInput() throws IOException {
        final byte[] caller = Files.readAllBytes(TestResources.mpqCopy("normalMap"));

        JMpqEditor editor = new JMpqEditor(caller, MPQOpenOption.FORCE_V0);
        // Scribble over the caller's array while the editor is open.
        java.util.Arrays.fill(caller, (byte) 0);
        editor.insertByteArray("added.txt", "x".getBytes(StandardCharsets.UTF_8));
        editor.close();

        try (MpqArchive rebuilt =
                 MpqArchive.open(editor.getOutputByteArray(), MpqOpenOptions.warcraft3())) {
            Assert.assertTrue(rebuilt.contains("added.txt"));
            Assert.assertTrue(rebuilt.contains("war3map.j"),
                "the original contents should have survived the caller's scribbling");
        }
    }

    /**
     * An empty external list file is complete for an archive that holds nothing,
     * so it must restore writability. Requiring a resolved name left a fresh
     * archive unable to receive its first file.
     */
    @Test
    public void emptyExternalListfileIsAcceptedWhenNothingIsAtRisk() throws IOException {
        // An archive with no listfile and no files: nothing is at risk.
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withListfile(false))
            .toByteArray();
        Path mpq = TestResources.scratchDir("empty-external").resolve("m.w3x");
        Files.write(mpq, image);

        Path emptyListfile = TestResources.scratchDir("empty-lf").resolve("listfile.txt");
        Files.writeString(emptyListfile, "");

        try (JMpqEditor editor = new JMpqEditor(mpq, MPQOpenOption.FORCE_V0)) {
            Assert.assertFalse(editor.isCanWrite(), "no listfile means read-only to start");
            editor.setExternalListfile(emptyListfile.toFile());
            Assert.assertTrue(editor.isCanWrite(),
                "an empty listfile is complete when the archive holds nothing");
            editor.insertByteArray("first.txt", "hello".getBytes(StandardCharsets.UTF_8));
        }

        try (MpqArchive archive = MpqArchive.open(mpq, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(new String(archive.read("first.txt"), StandardCharsets.UTF_8),
                "hello");
        }
    }

    /** The writer refuses a format version it cannot write, at construction. */
    @Test
    public void unwritableFormatVersionIsRefusedEarly() {
        for (int version : List.of(2, 3, 4, -1)) {
            Assert.expectThrows(IllegalArgumentException.class,
                () -> MpqWriteOptions.defaults().withFormatVersion(version));
        }
    }
}
