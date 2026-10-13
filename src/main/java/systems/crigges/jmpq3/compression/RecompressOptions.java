package systems.crigges.jmpq3.compression;

public class RecompressOptions {
    public boolean recompress;
    public boolean useZopfli = false;
    /** Compress large deflate sector batches on the common fork/join pool; Zopfli stays serial. */
    public boolean parallelCompression = true;
    public int iterations = 16;
    public int newSectorSizeShift = 3;

    public RecompressOptions(boolean recompress) {
        this.recompress = recompress;
    }
}
