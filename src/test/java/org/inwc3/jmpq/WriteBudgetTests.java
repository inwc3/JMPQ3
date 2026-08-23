package org.inwc3.jmpq;

import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Budgets for assembling an archive, so a performance regression fails the
 * build instead of being noticed months later.
 *
 * <h2>Why these assert on counters rather than on time</h2>
 * A wall-clock budget tight enough to catch a real regression is also tight
 * enough to flake on a shared CI runner, and a budget loose enough not to flake
 * catches almost nothing. So the regressions that matter are pinned with
 * deterministic counters instead: how many times the image buffer reallocated,
 * and how much memory it ended up holding. Those numbers do not vary between
 * runs or machines.
 * <p>
 * There is one time-based check, deliberately loose, purely as a net for a
 * catastrophic regression - something that makes assembly orders of magnitude
 * slower rather than merely worse.
 *
 * <h2>What is actually being protected</h2>
 * Growth is the dominant cost of assembling a large archive. The buffer doubles
 * when it runs out of room, and each doubling copies everything written so far,
 * so a buffer that starts small copies roughly the whole archive again on the
 * way up and peaks at about 1.5 times its final size. For a 500 MB map that is
 * the difference between fitting in a normal heap and not: the estimate used to
 * count a file inserted by path as costing nothing, and assembling a map from a
 * directory is exactly how a build tool does it.
 * <p>
 * This test lives in {@code org.inwc3.jmpq} so it can read those counters
 * without the library exposing them publicly.
 */
public class WriteBudgetTests {

    /** Big enough for growth to show, small enough to keep CI quick. */
    private static final int FILE_SIZE = 2 * 1024 * 1024;

    private static final int FILE_COUNT = 12;

    /**
     * The buffer may hold this much more than the archive it produced. Slack
     * covers the worst-case sector reserve and the tables; it is not room for a
     * future "just allocate plenty" shortcut.
     */
    private static final double CAPACITY_TOLERANCE = 1.15;

    private static byte[] content(int seed) {
        final byte[] bytes = new byte[FILE_SIZE];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static Path scratch(String hint) throws IOException {
        return Files.createTempDirectory("jmpq3-budget-" + hint);
    }

    /** Files supplied in memory must not grow the buffer at all. */
    @Test
    public void assemblingFromMemoryDoesNotGrowTheBuffer() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.fast());
        for (int i = 0; i < FILE_COUNT; i++) {
            writer.put("war3mapImported/asset" + i + ".blp", content(i));
        }

        final MpqImageBuffer image = writer.build();

        Assert.assertEquals(image.growths(), 0,
            "the writer knows every file's size up front, so it should size the image once;"
                + " it reallocated " + image.growths() + " times, copying "
                + image.bytesCopiedByGrowth() + " bytes");
        Assert.assertEquals(image.bytesCopiedByGrowth(), 0L);
    }

    /**
     * Files supplied by path must not grow the buffer either.
     * <p>
     * This is the case that regressed: the size estimate counted a
     * {@code Content.File} as zero bytes, so assembling a map from a directory
     * grew the buffer from nothing, which is the one shape a build tool always
     * uses.
     */
    @Test
    public void assemblingFromDiskDoesNotGrowTheBuffer() throws IOException {
        final Path directory = scratch("disk");
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.fast());
        for (int i = 0; i < FILE_COUNT; i++) {
            final Path file = directory.resolve("asset" + i + ".blp");
            Files.write(file, content(i));
            writer.put("war3mapImported/asset" + i + ".blp", file);
        }

        final MpqImageBuffer image = writer.build();

        Assert.assertEquals(image.growths(), 0,
            "a file inserted by path has a knowable size; counting it as free is what made"
                + " assembling a map from a directory reallocate " + image.growths() + " times");
        Assert.assertEquals(image.bytesCopiedByGrowth(), 0L);
    }

    /** Rebuilding an existing archive must not grow the buffer. */
    @Test
    public void rebuildingDoesNotGrowTheBuffer() throws IOException {
        final MpqArchiveWriter first = MpqArchiveWriter.create(MpqWriteOptions.fast());
        for (int i = 0; i < FILE_COUNT; i++) {
            first.put("war3mapImported/asset" + i + ".blp", content(i));
        }
        final byte[] original = first.toByteArray();

        try (MpqArchive archive = MpqArchive.open(original, MpqOpenOptions.defaults())) {
            final MpqArchiveWriter rebuild =
                MpqArchiveWriter.from(archive, MpqWriteOptions.fast());
            rebuild.put("war3map.j", "// compiled".getBytes(StandardCharsets.UTF_8));

            final MpqImageBuffer image = rebuild.build();
            Assert.assertEquals(image.growths(), 0,
                "a verbatim rebuild knows every stored size from the source block table");
        }
    }

    /**
     * The buffer must not hold much more than the archive it produced.
     * <p>
     * The counterpart to the growth budget: sizing the buffer generously would
     * satisfy that one and quietly double the memory a large map needs.
     */
    @Test
    public void theBufferIsNotGrosslyOversized() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.fast());
        for (int i = 0; i < FILE_COUNT; i++) {
            writer.put("war3mapImported/asset" + i + ".blp", content(i));
        }

        final MpqImageBuffer image = writer.build();
        final int produced = image.toByteArray().length;
        final long allowed = (long) (produced * CAPACITY_TOLERANCE);

        Assert.assertTrue(image.capacity() <= allowed,
            "image buffer holds " + image.capacity() + " bytes for a " + produced
                + " byte archive, above the " + allowed + " allowed");
        Assert.assertTrue(produced > FILE_COUNT * (long) FILE_SIZE,
            "the fixture should be dominated by its file data");
    }

    /**
     * Pre-sizing must never reserve more than growing would have peaked at.
     * <p>
     * Summing the inputs is only sound when the output size follows from them.
     * With compression it does not: a highly compressible corpus produces an
     * archive a fraction of its input, so reserving the sum would hold memory
     * the archive never needs -- and could fail outright on input that the old
     * grow-as-you-go path completed. Pre-sizing is a performance change; it is
     * not allowed to shrink what the library can handle.
     */
    @Test
    public void recompressingDoesNotReserveTheWholeUncompressedCorpus() throws IOException {
        // Compresses to almost nothing, so raw sum and output diverge sharply.
        final byte[] compressible = new byte[4 * 1024 * 1024];

        final RecompressOptions deflate = new RecompressOptions(true);
        final MpqArchiveWriter writer = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withRecompression(deflate));
        for (int i = 0; i < FILE_COUNT; i++) {
            writer.put("war3mapImported/blank" + i + ".bin", compressible);
        }

        final long rawSum = (long) FILE_COUNT * compressible.length;
        final MpqImageBuffer image = writer.build();
        final int produced = image.toByteArray().length;

        Assert.assertTrue(produced < rawSum / 10,
            "the fixture should compress hard: " + produced + " from " + rawSum);
        Assert.assertTrue(image.capacity() < rawSum / 2,
            "reserved " + image.capacity() + " bytes for a " + produced
                + " byte archive built from " + rawSum + " bytes of input;"
                + " sizing from the raw sum is worse than growing");
    }

    /**
     * {@link MpqWriteOptions#fast()} really does store rather than compress.
     * <p>
     * Checked against compressible content, where the two settings cannot look
     * the same: stored output stays the size of its input, and deflate does not.
     */
    @Test
    public void fastStoresAndRecompressedCompresses() throws IOException {
        final byte[] compressible = "abcabcabc".repeat(200_000).getBytes(StandardCharsets.UTF_8);

        final byte[] stored = MpqArchiveWriter.create(MpqWriteOptions.fast())
            .put("a.txt", compressible)
            .toByteArray();

        final RecompressOptions deflate = new RecompressOptions(true);
        final byte[] compressed = MpqArchiveWriter
            .create(MpqWriteOptions.defaults().withRecompression(deflate))
            .put("a.txt", compressible)
            .toByteArray();

        Assert.assertTrue(stored.length > compressible.length,
            "storing cannot shrink the data, so the archive holds all of it");
        Assert.assertTrue(compressed.length < stored.length / 10,
            "deflate should be dramatically smaller on this input: stored " + stored.length
                + " versus compressed " + compressed.length);

        // Both must read back identically; fast is a speed choice, not a lossy one.
        try (MpqArchive a = MpqArchive.open(stored, MpqOpenOptions.defaults());
             MpqArchive b = MpqArchive.open(compressed, MpqOpenOptions.defaults())) {
            Assert.assertEquals(a.read("a.txt"), compressible);
            Assert.assertEquals(b.read("a.txt"), compressible);
        }

        // That fast() *is* the store path is asserted on what it produces, not
        // on how long it took. An earlier version timed the two and compared
        // them, which contradicted this class's own reasoning: a scheduler pause
        // or a GC in the one measured call is enough to invert a single sample.
        Assert.assertFalse(MpqWriteOptions.fast().recompression().recompress,
            "fast() must not ask for recompression");

        try (MpqArchive a = MpqArchive.open(stored, MpqOpenOptions.defaults());
             MpqArchive b = MpqArchive.open(compressed, MpqOpenOptions.defaults())) {
            final MpqFileEntry storedEntry = a.entry("a.txt").orElseThrow();
            final MpqFileEntry deflatedEntry = b.entry("a.txt").orElseThrow();

            // A stored sector occupies its natural length, so the file cannot
            // take less room than its content; a deflated one must take less.
            Assert.assertTrue(storedEntry.compressedSize() >= storedEntry.normalSize(),
                "fast() stored " + storedEntry.compressedSize() + " bytes for "
                    + storedEntry.normalSize() + " of content, so something compressed it");
            Assert.assertTrue(deflatedEntry.compressedSize() < deflatedEntry.normalSize() / 10,
                "deflate stored " + deflatedEntry.compressedSize() + " bytes for "
                    + deflatedEntry.normalSize() + " of highly compressible content");
        }
    }

    /**
     * A catastrophe net, not a budget.
     * <p>
     * The threshold is roughly a hundred times the measured cost, so it will not
     * flake on a loaded runner and will not catch a modest regression either.
     * The counter-based tests above are what protect against those. This is here
     * to fail loudly if assembly ever becomes accidentally quadratic.
     */
    @Test
    public void assemblyIsNotCatastrophicallySlow() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.fast());
        for (int i = 0; i < FILE_COUNT; i++) {
            writer.put("war3mapImported/asset" + i + ".blp", content(i));
        }

        final long start = System.nanoTime();
        final byte[] image = writer.toByteArray();
        final long millis = (System.nanoTime() - start) / 1_000_000;

        Assert.assertTrue(millis < 20_000,
            "assembling " + image.length + " bytes took " + millis + "ms");
    }
}
