package systems.crigges.jmpq3.compression;


import com.googlecode.pngtastic.core.processing.zopfli.Buffer;
import com.googlecode.pngtastic.core.processing.zopfli.Options;
import com.googlecode.pngtastic.core.processing.zopfli.Zopfli;

/**
 * Created by Frotty on 09.05.2017.
 */
public class ZopfliHelper {
    private final Zopfli compressor;

    public ZopfliHelper() {
        compressor = new Zopfli(8 * 1024 * 1024);
    }

    public byte[] deflate(byte[] bytes, int iterations) {
        Buffer output = compressor.compress(new Options(Options.BlockSplitting.FIRST, iterations), bytes);
        byte[] outputBytes = new byte[output.getSize()];
        System.arraycopy(output.getData(), 0, outputBytes, 0, output.getSize());
        return outputBytes;
    }
}
