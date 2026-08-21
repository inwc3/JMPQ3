package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-8: the extension hooks that replace the {@code w3p} branch.
 * <p>
 * That branch carried protection features as core API — a {@code fakeFilesCount}
 * parameter on {@code close}, a "maximise V0 tables" mode, and a generator for
 * plausible-looking Warcraft III file names. Per the maintainer's decision none
 * of that belongs in this library: it is one tool's policy, and baking it in
 * means every caller pays for it and the library has opinions about deceiving
 * MPQ readers.
 * <p>
 * Instead the writer exposes the two mechanisms such a tool actually needs —
 * explicit table capacity and extra block slots — and the policy lives in the
 * tool. These tests build what {@code w3p} built, using only public API, which
 * is the acceptance criterion for retiring the branch.
 */
public class ExtensionHookTests {

    /** The hash table size a maximised Warcraft III version 0 archive uses. */
    private static final int WARCRAFT_V0_HASH_TABLE_SIZE = 0x10000;

    /**
     * A maximised version 0 hash table, which is what the {@code w3p} branch's
     * "maximise tables" mode produced.
     */
    @Test
    public void hashTableCapacityCanBeMaximised() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withHashTableCapacity(WARCRAFT_V0_HASH_TABLE_SIZE))
            .put("war3map.j", "script".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().hashTableEntries(), WARCRAFT_V0_HASH_TABLE_SIZE);
            Assert.assertEquals(archive.read("war3map.j"),
                "script".getBytes(StandardCharsets.UTF_8));
            // The archive must still be a working archive, not just a big table.
            Assert.assertTrue(archive.isEnumerable());
        }
    }

    /**
     * Padding entries: a tool wanting decoy names adds them as ordinary files,
     * and controls the surrounding table sizes through the write options. No
     * library support for inventing names is needed or wanted.
     */
    @Test
    public void decoyEntriesAreJustFilesPlusCapacity() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(
            MpqWriteOptions.defaults()
                .withHashTableCapacity(1024)
                .withExtraBlockEntries(64));

        writer.put("war3map.j", "real script".getBytes(StandardCharsets.UTF_8));

        // The policy — what the decoys are called — belongs to the caller.
        final List<String> decoys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final String name = "Units\\decoy" + i + ".slk";
            decoys.add(name);
            writer.put(name, new byte[]{(byte) i});
        }

        final byte[] image = writer.toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().hashTableEntries(), 1024);
            // Real file, decoys, list file, plus the requested spare slots.
            Assert.assertEquals(archive.header().blockTableEntries(),
                1 + decoys.size() + 1 + 64);
            Assert.assertEquals(archive.read("war3map.j"),
                "real script".getBytes(StandardCharsets.UTF_8));
            for (String decoy : decoys) {
                Assert.assertTrue(archive.contains(decoy), decoy);
            }
            // Spare slots must not read as files.
            Assert.assertEquals(archive.blockCount(), 1 + decoys.size() + 1);
        }
    }

    /**
     * A capacity too small for the file count is refused rather than producing
     * an archive whose hash table cannot hold its own contents.
     */
    @Test
    public void insufficientCapacityIsRefused() {
        final MpqArchiveWriter writer = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withHashTableCapacity(4));
        for (int i = 0; i < 16; i++) {
            writer.put("file" + i + ".txt", new byte[]{1});
        }
        Assert.expectThrows(IOException.class, writer::toByteArray);
    }

    /** Capacity must be a power of two, as the format requires. */
    @Test
    public void capacityMustBeAPowerOfTwo() {
        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqWriteOptions.defaults().withHashTableCapacity(1000));
        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqWriteOptions.defaults().withHashTableCapacity(-8));
        Assert.expectThrows(IllegalArgumentException.class,
            () -> MpqWriteOptions.defaults().withExtraBlockEntries(-1));
        // 0 means "size it automatically", which is the default.
        Assert.assertEquals(MpqWriteOptions.defaults().hashTableCapacity(), 0);
    }

    /**
     * The list file can be suppressed, which is the other thing a protection
     * tool wants: an archive whose contents cannot be enumerated by name.
     */
    @Test
    public void listfileCanBeSuppressedForProtectedArchives() throws IOException {
        final byte[] image = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withListfile(false))
            .put("war3map.j", "hidden".getBytes(StandardCharsets.UTF_8))
            .toByteArray();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
            Assert.assertFalse(archive.isEnumerable(), "no listfile means no enumeration");
            Assert.assertFalse(archive.contains("(listfile)"));
            // Still readable if you know the name, which is the point.
            Assert.assertEquals(archive.read("war3map.j"), "hidden".getBytes(StandardCharsets.UTF_8));
            Assert.assertEquals(archive.filesLostOnRebuild(), archive.blockCount(),
                "every block is at risk without a listfile");
        }
    }

    /**
     * Everything a rebuild of a real map needs, combined: maximised tables, a
     * suppressed list file, and the original prefix preserved so the map still
     * loads. This is the shape {@code w3p} produced, from public API only.
     */
    @Test
    public void aProtectedRebuildOfARealMapIsExpressible() throws IOException {
        Path source = TestResources.mpqCopy("normalMap");
        final byte[] protectedImage;
        final List<String> originalNames;

        try (MpqArchive archive = MpqArchive.open(source, MpqOpenOptions.warcraft3())) {
            originalNames = archive.names();
            final MpqArchiveWriter writer = MpqArchiveWriter.from(archive,
                MpqWriteOptions.defaults()
                    .withHashTableCapacity(WARCRAFT_V0_HASH_TABLE_SIZE)
                    .withExtraBlockEntries(32)
                    .withListfile(false)
                    .withPrefix(true));
            protectedImage = writer.toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(protectedImage, MpqOpenOptions.warcraft3())) {
            Assert.assertEquals(archive.header().hashTableEntries(), WARCRAFT_V0_HASH_TABLE_SIZE);
            Assert.assertFalse(archive.contains("(listfile)"));
            Assert.assertTrue(archive.header().headerOffset() > 0, "the map prefix must survive");

            // Every original file is still there, reachable by name.
            for (String name : originalNames) {
                if (name.equals("(listfile)")) {
                    continue;
                }
                Assert.assertTrue(archive.contains(name), "lost " + name);
            }
        }
    }
}
