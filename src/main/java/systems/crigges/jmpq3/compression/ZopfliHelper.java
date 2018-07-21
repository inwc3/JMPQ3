package systems.crigges.jmpq3.compression;

import ru.eustas.zopfli.Buffer;
import ru.eustas.zopfli.Options;
import ru.eustas.zopfli.Zopfli;

/**
 * Created by Frotty on 09.05.2017.
 */
public class ZopfliHelper {
    private final Zopfli compressor;
    private final Options options;
    private static final byte[] ZLIB_MAGIC = {120, -38};

    public ZopfliHelper() {
        Options.OutputFormat outputType = Options.OutputFormat.DEFLATE;
        Options.BlockSplitting blockSplitting = Options.BlockSplitting.FIRST;

        compressor = new Zopfli(8 * 1024 * 1024);
        options = new Options(outputType, blockSplitting, 15);
    }

    public byte[] deflate(byte[] bytes, int iterations) {
        options.numIterations = iterations;
        Buffer output = compressor.compress(options, bytes);
        byte[] outputBytes = new byte[output.getSize() + 2];
        System.arraycopy(output.getData(), 0, outputBytes, 2, output.getSize());
        outputBytes[0] = ZLIB_MAGIC[0];
        outputBytes[1] = ZLIB_MAGIC[1];
        return outputBytes;
    }
}
