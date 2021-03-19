package systems.crigges.jmpq3.compression;


import ru.eustas.zopfli.Options;
import systems.crigges.jmpq3.DebugHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created by Frotty on 09.05.2017.
 */
public class ZopfliHelper {
    private final ru.eustas.zopfli.Zopfli compressor;

    public ZopfliHelper() {
        compressor = new ru.eustas.zopfli.Zopfli(4 * 1024 * 1024);
    }

    public byte[] deflate(byte[] bytes, int iterations) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            compressor.compress(new ru.eustas.zopfli.Options(Options.OutputFormat.ZLIB,
                    ru.eustas.zopfli.Options.BlockSplitting.FIRST, iterations),
                bytes, byteArrayOutputStream);

            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
