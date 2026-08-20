package systems.crigges.jmpq3.compression;

import ru.eustas.zopfli.Options;
import ru.eustas.zopfli.Zopfli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Zopfli backed deflate, used when {@link RecompressOptions#useZopfli} is set.
 * <p>
 * Each instance owns a compressor with its own scratch memory, so instances
 * must not be shared between threads. {@link CompressionUtil} creates one per
 * call.
 */
public class ZopfliHelper {
    private final Zopfli compressor;

    public ZopfliHelper() {
        compressor = new Zopfli(4 * 1024 * 1024);
    }

    /**
     * @param bytes      data to compress.
     * @param iterations zopfli iteration count; higher is smaller and slower.
     * @return a zlib stream.
     */
    public byte[] deflate(byte[] bytes, int iterations) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            compressor.compress(
                new Options(Options.OutputFormat.ZLIB, Options.BlockSplitting.FIRST, iterations),
                bytes, out);
            return out.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream cannot fail; anything here is a genuine
            // fault and must not be swallowed into a null return.
            throw new UncheckedIOException("Zopfli compression failed.", e);
        }
    }
}
