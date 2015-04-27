package de.peeeq.jmpq;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;

public class JzLibHelper {

	@SuppressWarnings("deprecation")
	public static byte[] inflate(byte[] bytes, int uncompSize) {
		byte[] uncomp = new byte[uncompSize];
		Inflater inf = new Inflater();
		inf.setInput(bytes);
		inf.setOutput(uncomp);
		while (inf.total_out < uncompSize && inf.total_in < bytes.length) {
			inf.avail_in = inf.avail_out = 1;
			int err = inf.inflate(JZlib.Z_NO_FLUSH);
			if (err == JZlib.Z_STREAM_END)
				break;
		}
		inf.end();
		return uncomp;
	}

	@SuppressWarnings("deprecation")
	public static byte[] deflate(byte[] bytes) {
		byte[] comp = new byte[bytes.length];
		Deflater def = null;
		try {
			def = new Deflater(JZlib.Z_BEST_COMPRESSION);
		} catch (GZIPException e) {
			throw new RuntimeException(e);
		}
		def.setInput(bytes);
		def.setOutput(comp);
		while (def.total_in != bytes.length && def.total_out < bytes.length) {
			def.avail_in = def.avail_out = 1; // force small buffers
			def.deflate(JZlib.Z_NO_FLUSH);
		}

		while (true) {
			def.avail_out = 1;
			int err = def.deflate(JZlib.Z_FINISH);
			if (err == JZlib.Z_STREAM_END)
				break;
		}
		byte[] temp = new byte[(int) def.getTotalOut()];
		System.arraycopy(comp, 0, temp, 0, (int) def.getTotalOut());
		def.end();
		return temp;
	}

}
