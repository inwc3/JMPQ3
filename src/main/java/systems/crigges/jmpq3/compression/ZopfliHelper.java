package systems.crigges.jmpq3.compression;


import systems.crigges.jmpq3.DebugHelper;

import java.io.ByteArrayOutputStream;

/**
 * Created by Frotty on 09.05.2017.
 */
public class ZopfliHelper {
    private final ru.eustas.zopfli.Zopfli compressor;
    private static final byte[] HEADER_MAGIC = new byte[]{120, -38};

    public ZopfliHelper() {
        compressor = new ru.eustas.zopfli.Zopfli(8 * 1024 * 1024);
    }

    public byte[] deflate(byte[] bytes, int iterations) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        compressor.compress(new ru.eustas.zopfli.Options(ru.eustas.zopfli.Options.OutputFormat.DEFLATE, ru.eustas.zopfli.Options.BlockSplitting.FIRST, iterations),
            bytes, byteArrayOutputStream);

        return DebugHelper.appendData(HEADER_MAGIC, byteArrayOutputStream.toByteArray());
    }
}
