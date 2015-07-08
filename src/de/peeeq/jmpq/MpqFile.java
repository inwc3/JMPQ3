 package de.peeeq.jmpq;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;

import com.google.common.io.LittleEndianDataInputStream;

import de.peeeq.jmpq.BlockTable.Block;

public class MpqFile {
	public static final int COMPRESSED = 0x00000200;
	public static final int ENCRYPTED = 0x00010000;
	public static final int SINGLEUNIT = 0x01000000;
	public static final int ADJUSTED_ENCRYPTED = 0x00020000;
	public static final int EXISTS = 0x80000000;

	private MappedByteBuffer buf;
	private Block block;
	private MpqCrypto crypto = null;
	private int sectorSize;
	private int offset;
	private int compSize;
	private int normalSize;
	private int flags;
	private int blockIndex;
	private String name;
	private int sectorCount;
	private int baseKey;
	private int sepIndex;

	public int getBlockIndex() {
		return blockIndex;
	}

	public void setBlockIndex(int blockIndex) {
		this.blockIndex = blockIndex;
	}

//	public MpqFile(File f, String name, int sectorSize) throws JMpqException {
//		byte[] arr = null;
//		try {
//			arr = Files.readAllBytes(f.toPath());
//		} catch (IOException e) {
//			throw new JMpqException("Could not read File: " + f.getName());
//		}
//		normalSize = arr.length;
//		this.name = name;
//		this.sectorSize = sectorSize;
//		int sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)));
//		sectors = new Sector[sectorCount];
//		for (int i = 0; i < arr.length; i += sectorSize) {
//			int length = sectorSize;
//			if (normalSize - i < sectorSize) {
//				length = normalSize - i;
//			}
//			byte[] temp = new byte[length];
//			System.arraycopy(arr, i, temp, 0, length);
//			sectors[i / sectorSize] = new Sector(temp);
//
//		}
//		compSize = 0;
//		for (Sector s : sectors) {
//			// + 4 for int in sot + 1 for compression flag
//			compSize += s.contentCompressed.length + 5;
//		}
//		// + 4 for end in sot
//		compSize += 4;
//		flags = COMPRESSED | EXISTS;
//	}

	@Override
	public String toString() {
		return "MpqFile [sectorSize=" + sectorSize + ", offset=" + offset + ", compSize=" + compSize + ", normalSize="
				+ normalSize + ", flags=" + flags + ", blockIndex=" + blockIndex + ", name=" + name + "]";
	}

	public void setOffset(int newOffset) {
		offset = newOffset;
	}

	public MpqFile(MappedByteBuffer buf, Block b, int sectorSize, String name) throws IOException, JMpqException {
		this.buf = buf;
		this.block = b;
		this.sectorSize = sectorSize;
		this.name = name;
		this.compSize = b.getCompressedSize();
		this.normalSize = b.getNormalSize();
		this.flags = (int) b.getFlags();
		this.sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)) + 1);
		this.baseKey = 0;
		this.sepIndex = name.lastIndexOf('\\');
		String pathlessName = name.substring(sepIndex + 1);
		if ((b.getFlags() & ENCRYPTED) == ENCRYPTED) {
			crypto = new MpqCrypto();
			baseKey = crypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
			if ((b.getFlags() & ADJUSTED_ENCRYPTED) == ADJUSTED_ENCRYPTED) {
				baseKey = ((baseKey + b.getFilePos()) ^ b.getNormalSize());
			}
		}
	}

	public int getOffset() {
		return offset;
	}

	public int getCompSize() {
		return compSize;
	}

	public int getNormalSize() {
		return normalSize;
	}

	public int getFlags() {
		return flags;
	}

	public String getName() {
		return name;
	}
	
	public void extractToFile(File f) throws IOException {
		extractToOutputStream(new FileOutputStream(f));
	}

	public void extractToOutputStream(OutputStream writer) throws IOException {
		if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
			ByteBuffer sotBuffer = null;
			buf.position(block.getFilePos());
			byte[] sot = new byte[sectorCount * 4];
			buf.get(sot);
			if (crypto != null) {
				sot = crypto.decryptBlock(sot, baseKey - 1);
			}
			sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
			int start = sotBuffer.getInt();
			int end = sotBuffer.getInt();
			int finalSize = 0;
			for (int i = 0; i < sectorCount - 1; i++) {
				buf.position(block.getFilePos() + start);
				byte[] arr = getSectorAsByteArray(buf, end - start);
				if(crypto != null){
					arr = crypto.decryptBlock(arr, baseKey + i);
				}
				if (block.getNormalSize() - finalSize <= sectorSize) {
					arr = decompressSector(arr, end - start, block.getNormalSize()- finalSize);
				} else {
					arr = decompressSector(arr, end - start, sectorSize);
				}
				writer.write(arr);
				
				finalSize += sectorSize;
				start = end;
				try {
					end = sotBuffer.getInt();
				} catch (BufferUnderflowException e) {
					break;
				}
			}
			writer.flush();
			writer.close();
		} else {
			buf.position(block.getFilePos());
			byte[] arr = getSectorAsByteArray(buf, compSize);
			if(crypto != null){
				arr = crypto.decryptBlock(arr, baseKey);
			}
			writer.write(arr);
			writer.flush();
			writer.close();
		}
	}
	
	
	private byte[] getSectorAsByteArray(MappedByteBuffer buf, int sectorSize){
		byte[] arr = new byte[sectorSize];
		buf.get(arr);
		return arr;
	}
	
	private byte[] decompressSector(byte[] sector, int normalSize, int uncompSize){
		if(normalSize == uncompSize){
			return sector;
		}else{
			byte compressionType = sector[0];
			if (((compressionType & 2) == 2)){
				return JzLibHelper.inflate(sector, 1 ,uncompSize);
			}
			return null;
		}
	}
}
