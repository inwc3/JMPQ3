package systems.crigges.jmpq3.compression;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;

public class JzLibHelper {
    private static Inflater inf = new Inflater();
    private static Deflater def = null;

    public static byte[] inflate(byte[] bytes, int offset, int uncompSize) {
        byte[] uncomp = new byte[uncompSize];
        inf.init();
        inf.setInput(bytes, offset, bytes.length - 1, false);
        inf.setOutput(uncomp);
        while ((inf.total_out < uncompSize) && (inf.total_in < bytes.length)) {
            inf.avail_in = (inf.avail_out = 1);
            int err = inf.inflate(0);
            if (err == 1)
                break;
        }
        inf.end();
        return uncomp;
    }

    public static byte[] deflate(byte[] bytes) {
        tryCreateDeflater();

        byte[] comp = new byte[bytes.length];
        def.init(9);
        def.setInput(bytes);
        def.setOutput(comp);
        while ((def.total_in != bytes.length) && (def.total_out < bytes.length)) {
            def.avail_in = (def.avail_out = 1);
            def.deflate(0);
        }
        int err;
        do {
            def.avail_out = 1;
            err = def.deflate(4);
        } while (err != 1);

        byte[] temp = new byte[(int) def.getTotalOut()];
        System.arraycopy(comp, 0, temp, 0, (int) def.getTotalOut());
        def.end();
        return temp;
    }

    private static void tryCreateDeflater() {
        if(def == null) {
            try {
                def = new Deflater(9);
            } catch (GZIPException e) {
                throw new RuntimeException(e);
            }
        }
    }
}