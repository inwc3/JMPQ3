package systems.crigges.jmpq3.compression;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateParameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.InflaterInputStream;

public class JzLibHelper {
    private static final Inflater inf = new Inflater();

    private static int defLvl = 0;
    private static Deflater def = null;


    public static byte[] inflate(byte[] bytes, int offset, int uncompSize) {
        Inflater inf = new Inflater(); // Create a new Inflater instance
        byte[] uncomp = new byte[uncompSize];
        try {
            // Set the input data correctly
            inf.setInput(bytes, offset, bytes.length - offset, false);
            inf.setOutput(uncomp);

            int err;
            do {
                // Perform the inflation without manually setting avail_in and avail_out
                err = inf.inflate(JZlib.Z_NO_FLUSH);
                if (err == JZlib.Z_STREAM_END) {
                    break;
                } else if (err != JZlib.Z_OK && err != JZlib.Z_BUF_ERROR) {
                    throw new RuntimeException("Inflater error: " + inf.msg);
                }
                // If no progress is made, break to avoid infinite loop
                if (inf.avail_in == 0 && inf.avail_out == 0) {
                    break;
                }
            } while (true);

            return uncomp;
        } finally {
            inf.end(); // Ensure resources are released
        }
    }

    static byte[] comp = new byte[1024];

    public static byte[] deflate(byte[] bytes, boolean strongDeflate) {
        boolean created = tryCreateDeflater(strongDeflate ? 9 : 1);

        if (comp.length < bytes.length) {
            comp = new byte[bytes.length];
        }
        if (!created) {
            def.init(strongDeflate ? 9 : 1);
        }
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

    private static boolean tryCreateDeflater(int lvl) {
        if (def == null || lvl != defLvl) {
            try {
                def = new Deflater(lvl);
                defLvl = lvl;
                return true;
            } catch (GZIPException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }
}