package systems.crigges.jmpq3.compression;

public class RecompressOptions {
    public boolean recompress;
    public boolean useZopfli = false;
    public int iterations = 15;
    public int newSectorSizeShift = 3;

    public RecompressOptions(boolean recompress) {
        this.recompress = recompress;
    }
}
