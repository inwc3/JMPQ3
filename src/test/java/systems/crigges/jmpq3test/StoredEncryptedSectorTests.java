package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqHeader;
import org.inwc3.jmpq.MpqOpenOptions;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An encrypted file stored without compression, spanning several sectors.
 * <p>
 * Absence of a compression flag removes the sector <em>offset table</em>, not
 * the sectors: the file still occupies fixed-size sectors and each is encrypted
 * with its own {@code key + index}. StormLib decrypts inside the per-sector
 * loop in {@code ReadMpqSectors} regardless of the compression flags, which only
 * decide whether an offset table is consulted.
 * <p>
 * Both implementations used to decrypt the whole file with the base key, so the
 * first sector decoded and every sector after it was returned as garbage. No
 * shipped fixture contains such a file, which is why it went unnoticed through
 * a full golden-file suite, so the archive is built here by hand.
 */
public class StoredEncryptedSectorTests {

    private static final int SECTOR_SHIFT = 3;
    private static final int SECTOR_SIZE = 512 << SECTOR_SHIFT;
    private static final String NAME = "stored.bin";
    private static final char SEPARATOR = '\t';
    private static final char LINE_END = '\n';

    /** The new core must decode every sector, not just the first. */
    @Test
    public void newCoreDecodesEverySector() throws IOException {
        final byte[] content = payload(SECTOR_SIZE * 3 + 17);
        final byte[] image = buildArchive(content);

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertTrue(archive.contains(NAME));
            Assert.assertEquals(archive.read(NAME), content);
        }
    }

    /** The compatibility layer must decode it too. */
    @Test
    public void compatibilityLayerDecodesEverySector() throws IOException {
        final byte[] content = payload(SECTOR_SIZE * 3 + 17);
        final byte[] image = buildArchive(content);

        try (JMpqEditor editor = new JMpqEditor(image, MPQOpenOption.READ_ONLY)) {
            Assert.assertTrue(editor.hasFile(NAME));
            Assert.assertEquals(editor.extractFileAsBytes(NAME), content);
        }
    }

    /** Sizes on either side of a sector boundary, where off-by-ones live. */
    @Test
    public void sectorBoundarySizesDecode() throws IOException {
        for (int size : new int[]{1, SECTOR_SIZE - 1, SECTOR_SIZE, SECTOR_SIZE + 1,
            SECTOR_SIZE * 2, SECTOR_SIZE * 2 + 1}) {
            final byte[] content = payload(size);
            final byte[] image = buildArchive(content);

            try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
                Assert.assertEquals(archive.read(NAME), content, "size " + size);
            }
            try (JMpqEditor editor = new JMpqEditor(image, MPQOpenOption.READ_ONLY)) {
                Assert.assertEquals(editor.extractFileAsBytes(NAME), content, "size " + size);
            }
        }
    }

    /** A single-sector file was always fine; this pins that it stays fine. */
    @Test
    public void singleSectorStillDecodes() throws IOException {
        final byte[] content = payload(100);
        final byte[] image = buildArchive(content);
        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read(NAME), content);
        }
    }

    /**
     * Also written to disk once, so the file-backed read path is exercised and
     * not only the in-memory one.
     */
    @Test
    public void fileBackedReadDecodesEverySector() throws IOException {
        final byte[] content = payload(SECTOR_SIZE * 2 + 5);
        final Path path = TestResources.scratchDir("stored-encrypted").resolve("built.mpq");
        Files.write(path, buildArchive(content));

        try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read(NAME), content);
        }
    }

    /**
     * Exports the hand-built archive plus its expected content so
     * {@code tools/mpqref.py} can confirm it independently. The reference had
     * the identical whole-block decryption bug, so it would have agreed with the
     * wrong answer; fixing both and having them agree is the actual
     * verification.
     */
    @Test
    public void exportForReferenceVerification() throws IOException {
        final Path out = Path.of("build", "stored-encrypted");
        final Path archives = out.resolve("archives");
        Files.createDirectories(archives);

        final StringBuilder expected =
            new StringBuilder("# archive\tname\tsize\tmd5\n");

        int exported = 0;
        for (int size : new int[]{100, SECTOR_SIZE, SECTOR_SIZE * 3 + 17}) {
            final byte[] content = payload(size);
            final String fixture = "stored-encrypted-" + size + ".mpq";
            Files.write(archives.resolve(fixture), buildArchive(content));
            expected.append(fixture).append(SEPARATOR).append(NAME).append(SEPARATOR)
                .append(content.length).append(SEPARATOR).append(TestHelper.md5(content)).append(LINE_END);
            exported++;
        }

        Assert.assertEquals(exported, 3);
        Files.writeString(out.resolve("expected.tsv"), expected.toString(),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Deterministic, poorly compressible filler so sectors differ. */
    private static byte[] payload(int length) {
        final byte[] out = new byte[length];
        int state = 0x2545F491;
        for (int i = 0; i < length; i++) {
            state = state * 1103515245 + 12345;
            out[i] = (byte) (state >>> 17);
        }
        return out;
    }

    /**
     * Builds a minimal version 0 archive holding one encrypted, uncompressed,
     * sectored file.
     * <p>
     * Layout: 32-byte header, file data, hash table, block table. The file has
     * no sector offset table, because it carries no compression flag, but its
     * sectors are encrypted individually.
     */
    private static byte[] buildArchive(byte[] content) throws IOException {
        final int flags = systems.crigges.jmpq3.MpqFile.EXISTS
            | systems.crigges.jmpq3.MpqFile.ENCRYPTED;
        final int hashEntries = 4;
        final int dataOffset = MpqHeader.SIZE_BY_VERSION[0];

        // Encrypt sector by sector, exactly as the format requires.
        final byte[] stored = content.clone();
        final int baseKey = MpqNames.sectorKey(NAME, flags, dataOffset, content.length);
        int remaining = content.length;
        for (int i = 0; remaining > 0; i++) {
            final int length = Math.min(SECTOR_SIZE, remaining);
            final byte[] sector = new byte[length];
            System.arraycopy(stored, i * SECTOR_SIZE, sector, 0, length);
            new MPQEncryption(baseKey + i, false).processSingle(ByteBuffer.wrap(sector));
            System.arraycopy(sector, 0, stored, i * SECTOR_SIZE, length);
            remaining -= length;
        }

        final int hashOffset = dataOffset + stored.length;
        final int blockOffset = hashOffset + hashEntries * 16;
        final int total = blockOffset + 16;

        final ByteBuffer image = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        image.putInt(MpqHeader.ARCHIVE_SIGNATURE);
        image.putInt(MpqHeader.SIZE_BY_VERSION[0]);
        image.putInt(total);
        image.putShort((short) 0);
        image.putShort((short) SECTOR_SHIFT);
        image.putInt(hashOffset);
        image.putInt(blockOffset);
        image.putInt(hashEntries);
        image.putInt(1);
        image.put(stored);

        final HashTable hashTable = new HashTable(hashEntries);
        hashTable.setFileBlockIndex(NAME, HashTable.DEFAULT_LOCALE, 0);
        final ByteBuffer hash = ByteBuffer.allocate(hashEntries * 16).order(ByteOrder.LITTLE_ENDIAN);
        hashTable.writeToBuffer(hash);
        hash.flip();
        new MPQEncryption(tableKey("(hash table)"), false).processSingle(hash);
        hash.flip();
        image.put(hash);

        final ByteBuffer block = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        block.putInt(dataOffset);
        block.putInt(stored.length);
        block.putInt(content.length);
        block.putInt(flags);
        block.flip();
        new MPQEncryption(tableKey("(block table)"), false).processSingle(block);
        block.flip();
        image.put(block);

        return image.array();
    }

    private static int tableKey(String name) {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process(name);
        return hasher.getHash();
    }
}
