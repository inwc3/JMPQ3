package de.peeeq.jmpq;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BinFileWriter implements Closeable {
	private final RandomAccessFile writer;

	public BinFileWriter(File newFile) throws JMpqException {
		try {
			writer = new RandomAccessFile(newFile, "rw");
		} catch (FileNotFoundException e) {
			throw new JMpqException("File not found: " + newFile);
		}
	}

	private void writeBytes(byte[] bytes) throws IOException {
		writer.write(bytes);
	}

	public void writeByte(byte b) throws IOException {
		writer.write(b);
	}

	public void writeString(String s) throws IOException {
		byte[] bytes = null;
		try {
			bytes = s.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		writeBytes(bytes);
		writeByte((byte) 0);
	}

	public void writeInt(int i) throws IOException {
		writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array());
	}

	public void writeShort(short i) throws IOException {
		writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(i).array());
	}

	public void writeFloat(float f) throws IOException {
		writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array());
	}

	public void writeFourchar(String s) throws IOException {
		if (s.length() != 4) {
			throw new IllegalArgumentException("String length != 4");
		}
		writeBytes(s.getBytes());
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}
}