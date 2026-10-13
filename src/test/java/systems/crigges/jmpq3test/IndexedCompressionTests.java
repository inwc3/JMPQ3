package systems.crigges.jmpq3test;

import org.inwc3.jmpq.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import systems.crigges.jmpq3.HashTable;
import systems.crigges.jmpq3.MpqNames;
import systems.crigges.jmpq3.compression.RecompressOptions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

public class IndexedCompressionTests {
    private static final class CountingTable extends HashTable {
        int scans;

        CountingTable() {
            super(4096);
        }

        @Override
        public List<Mapping> mappings() {
            scans++;
            return super.mappings();
        }
    }

    @Test
    public void localeEnumerationScansOnceAndTracksMutations() throws IOException {
        final CountingTable table = new CountingTable();
        for (int i = 0; i < 1000; i++) {
            table.setFileBlockIndex("dir/file" + i, (short) 0, i);
            table.setFileBlockIndex("dir/file" + i, (short) 0x407, i);
        }
        final List<HashTable.Mapping> expected = table.mappings();
        table.scans = 0;
        for (int i = 0; i < 1000; i++) {
            final String name = "DIR/FILE" + i;
            Assert.assertEquals(table.localesOf(name), expected.stream()
                .filter(mapping -> mapping.key() == MpqNames.fileKey(name))
                .map(HashTable.Mapping::locale).toList());
        }
        Assert.assertEquals(table.scans, 1, "one table scan per file is quadratic");
        table.removeFile("dir/file0", (short) 0x407);
        Assert.assertEquals(table.localesOf("dir/file0"), List.of((short) 0));
        table.setFileBlockIndex("dir/file0", (short) 0x40c, 1000);
        Assert.assertTrue(table.localesOf("dir/file0").contains((short) 0x40c));
        table.setFileBlockIndex("dir/file0", (short) 0, 1000);
        table.setBlockTableSize(1000);
        Assert.assertTrue(table.localesOf("dir/file0").isEmpty());
        final ByteBuffer empty = ByteBuffer.allocate(4096 * 16);
        new HashTable(4096).writeToBuffer(empty);
        empty.flip();
        table.readFromBuffer(empty);
        Assert.assertTrue(table.localesOf("dir/file1").isEmpty());
        Assert.assertEquals(table.size(), 0);
    }

    @Test
    public void writerNameIndexTracksReplacementAndRemoval() throws IOException {
        final MpqArchiveWriter writer = MpqArchiveWriter.create(MpqWriteOptions.defaults());
        for (int i = 0; i < 1000; i++) {
            writer.put("dir/file" + i, new byte[]{1});
        }
        writer.put("DIR/FILE0", (short) 0, new byte[]{2});
        writer.put("dir/file0", (short) 0x407, new byte[]{3});
        Assert.assertTrue(writer.remove("DIR/FILE0", (short) 0));
        Assert.assertTrue(writer.contains("dir/file0"));
        Assert.assertFalse(writer.contains("dir\\file0"));
        Assert.assertFalse(writer.remove("dir/file0", (short) 0));
        Assert.assertTrue(writer.remove("DIR/FILE0"));
        Assert.assertFalse(writer.contains("dir/file0"));
        Assert.assertFalse(writer.remove("dir/file0"));
        writer.put("dir/file0", (short) 0x40c, new byte[]{4});
        try (MpqArchive source = MpqArchive.open(writer.toByteArray(), MpqOpenOptions.defaults())) {
            final MpqArchiveWriter rebuilt = MpqArchiveWriter.from(source, MpqWriteOptions.defaults());
            Assert.assertTrue(rebuilt.contains("DIR/FILE0"));
            Assert.assertTrue(rebuilt.remove("dir/file0", (short) 0x40c));
            Assert.assertFalse(rebuilt.contains("dir/file0"));
            Assert.assertEquals(rebuilt.names().size(), 999);
        }
    }

    @Test
    public void parallelSectorsMatchSerialWithEncryptionAndChecksums() throws IOException {
        for (boolean zopfli : List.of(false, true)) {
            final byte[] content = new byte[zopfli ? 4096 * 9 + 17 : 4096 * 145 + 17];
            new Random(42).nextBytes(content);
            // Mix compressible and stored sectors, and end on a partial batch.
            java.util.Arrays.fill(content, 0, content.length / 2, (byte) 7);
            final RecompressOptions strategy = new RecompressOptions(true);
            strategy.useZopfli = zopfli;
            strategy.iterations = 1;
            final int flags = MpqFileEntry.FLAG_EXISTS | MpqFileEntry.FLAG_COMPRESSED
                | MpqFileEntry.FLAG_ENCRYPTED | MpqFileEntry.FLAG_ADJUSTED_KEY
                | MpqFileEntry.FLAG_SECTOR_CRC;
            final ByteBuffer serial = ByteBuffer.allocate(content.length * 2);
            final ByteBuffer parallel = ByteBuffer.allocate(content.length * 2);
            strategy.parallelCompression = false;
            MpqSectorWriter.writeInto(serial, content, 4096, "dir/file", flags, 32, strategy);
            strategy.parallelCompression = true;
            MpqSectorWriter.writeInto(parallel, content, 4096, "dir/file", flags, 32, strategy);
            Assert.assertEquals(parallel.position(), serial.position());
            Assert.assertEquals(parallel.array(), serial.array());

            final MpqWriteOptions options = MpqWriteOptions.defaults().withRecompression(strategy)
                .withSectorChecksums(true);
            final byte[] image = MpqArchiveWriter.create(options).put("dir/file", content).toByteArray();
            try (MpqArchive archive = MpqArchive.open(image,
                MpqOpenOptions.defaults().withSectorChecksumVerification(true))) {
                Assert.assertEquals(archive.read("dir/file"), content);
            }
        }
    }
}
