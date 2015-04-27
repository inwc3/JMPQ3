package de.peeeq.jmpq;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import com.google.common.io.LittleEndianDataInputStream;

import de.peeeq.jmpq.BlockTable.Block;

public class MpqFile {
	public static final int COMPRESSED = 0x00000200;
	public static final int ENCRYPTED = 0x00010000;
	public static final int SINGLEUNIT = 0x01000000;
	public static final int ADJUSTED_ENCRYPTED = 0x00020000;
	public static final int EXISTS = 0x80000000;

	private Sector[] sectors;
	private int sectorSize;
	private int offset;
	private int compSize;
	private int normalSize;
	private int flags;
	private int blockIndex;

	public int getBlockIndex() {
		return blockIndex;
	}

	public void setBlockIndex(int blockIndex) {
		this.blockIndex = blockIndex;
	}

	private String name;

	public MpqFile(File f, String name, int sectorSize) throws JMpqException {
		byte[] arr = null;
		try {
			arr = Files.readAllBytes(f.toPath());
		} catch (IOException e) {
			throw new JMpqException("Could not read File: " + f.getName());
		}
		normalSize = arr.length;
		this.name = name;
		this.sectorSize = sectorSize;
		int sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)));
		sectors = new Sector[sectorCount];
		for (int i = 0; i < arr.length; i += sectorSize) {
			int length = sectorSize;
			if (normalSize - i < sectorSize) {
				length = normalSize - i;
			}
			byte[] temp = new byte[length];
			System.arraycopy(arr, i, temp, 0, length);
			sectors[i / sectorSize] = new Sector(temp);

		}
		compSize = 0;
		for (Sector s : sectors) {
			// + 4 for int in sot + 1 for compression flag
			compSize += s.contentCompressed.length + 5;
		}
		// + 4 for end in sot
		compSize += 4;
		flags = COMPRESSED | EXISTS;
	}

	public MpqFile(byte[] arr, String name, int sectorSize) throws JMpqException {
		normalSize = arr.length;
		this.name = name;
		this.sectorSize = sectorSize;
		int sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)));
		sectors = new Sector[sectorCount];
		for (int i = 0; i < arr.length; i += sectorSize) {
			int length = sectorSize;
			if (normalSize - i < sectorSize) {
				length = normalSize - i;
			}
			byte[] temp = new byte[length];
			System.arraycopy(arr, i, temp, 0, length);
			sectors[i / sectorSize] = new Sector(temp);

		}
		compSize = 0;
		for (Sector s : sectors) {
			// + 4 for int in sot + 1 for compression flag
			compSize += s.contentCompressed.length + 5;
		}
		// + 4 for end in sot
		compSize += 4;
		flags = COMPRESSED | EXISTS;
	}

	@Override
	public String toString() {
		return "MpqFile [sectorSize=" + sectorSize + ", offset=" + offset + ", compSize=" + compSize + ", normalSize="
				+ normalSize + ", flags=" + flags + ", blockIndex=" + blockIndex + ", name=" + name + "]";
	}

	public void setOffset(int newOffset) {
		offset = newOffset;
	}

	public byte[] getFileAsByteArray(int offset) {
		this.offset = offset;
		int ceil = (int) Math.ceil((double) normalSize / (double) sectorSize);
		int sotSize = (ceil + 1) * 4;
		byte[] secs = new byte[compSize - sotSize];
		byte[] sot = new byte[sotSize];
		ByteBuffer sotBuffer = ByteBuffer.allocate(sotSize);
		sotBuffer.order(ByteOrder.LITTLE_ENDIAN);
		int offsetHelper = 0;
		for (Sector s : sectors) {
			sotBuffer.putInt(sotSize + offsetHelper);
			if (s.isCompressed) {
				// Compression flag
				secs[offsetHelper] = 2;
				System.arraycopy(s.contentCompressed, 0, secs, offsetHelper + 1, s.contentCompressed.length);
				offsetHelper += s.contentCompressed.length + 1;
			} else {
				System.arraycopy(s.contentCompressed, 0, secs, offsetHelper, s.contentCompressed.length);
				offsetHelper += s.contentCompressed.length;
			}
		}
		sotBuffer.putInt(sectors.length * 4 + 4 + offsetHelper);
		sotBuffer.position(0);
		sotBuffer.get(sot);
		byte[] file = new byte[compSize];
		System.arraycopy(sot, 0, file, 0, sot.length);
		System.arraycopy(secs, 0, file, sot.length, secs.length);
		return file;
	}

	public MpqFile(byte[] fileAsArray, Block b, int sectorSize, String name) throws IOException, JMpqException {
		this.sectorSize = sectorSize;
		this.name = name;
		this.compSize = b.getCompressedSize();
		this.normalSize = b.getNormalSize();
		this.flags = (int) b.getFlags();
		int sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)) + 1);
		MpqCrypto crypto = null;
		int baseKey = 0;
		int sepIndex = name.lastIndexOf('\\');
		String pathlessName = name.substring(sepIndex + 1);
		System.out.println("pathless" + pathlessName);
		if ((b.getFlags() & ENCRYPTED) == ENCRYPTED) {
			crypto = new MpqCrypto();
			baseKey = crypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
			if ((b.getFlags() & ADJUSTED_ENCRYPTED) == ADJUSTED_ENCRYPTED) {
				baseKey = ((baseKey + b.getFilePos()) ^ b.getNormalSize());
			}
		}
		if ((b.getFlags() & COMPRESSED) == COMPRESSED) {
			DataInput in = null;
			if (crypto == null) {
				in = new LittleEndianDataInputStream(new ByteArrayInputStream(fileAsArray, b.getFilePos(),
						fileAsArray.length));
			} else {
				byte[] sot = new byte[sectorCount * 4];
				System.arraycopy(fileAsArray, b.getFilePos(), sot, 0, sot.length);
				sot = crypto.decryptBlock(sot, baseKey - 1);
				in = new LittleEndianDataInputStream(new ByteArrayInputStream(sot));
			}
			sectors = new Sector[sectorCount - 1];
			int start = in.readInt();
			int end = in.readInt();
			int finalSize = 0;
			for (int i = 0; i < sectorCount - 1; i++) {
				if (b.getNormalSize() - finalSize <= sectorSize) {
					System.out.println(b.getFilePos());
					System.out.println(normalSize);
					System.out.println(name);
					System.out.println("start = " + start + "   end = " + end);
					byte[] temp = new byte[end - start];
					System.arraycopy(fileAsArray, b.getFilePos() + start, temp, 0, temp.length);
					try {
						sectors[i] = new Sector(temp, end - start, b.getNormalSize() - finalSize, crypto, baseKey + i);
					} catch (Exception e) {
						throw new JMpqException(e.getMessage() + " for file " + name);
					}
					break;
				} else {
					byte[] temp = new byte[end - start];
					System.arraycopy(fileAsArray, b.getFilePos() + start, temp, 0, temp.length);
					sectors[i] = new Sector(temp, end - start, sectorSize, crypto, baseKey + i);
					
				}
				finalSize += sectorSize;
				start = end;
				try {
					end = in.readInt();
				} catch (IOException e) {
					break;
				}
			}
		} else {
			sectors = new Sector[1];
			byte[] temp = new byte[compSize];
			System.arraycopy(fileAsArray, b.getFilePos(), temp, 0, temp.length);
			sectors[0] = new Sector(temp, compSize, normalSize, crypto, baseKey);
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
		byte[] fullFile = new byte[normalSize];
		int i = 0;
		for (Sector s : sectors) {
			System.arraycopy(s.contentUnCompressed, 0, fullFile, i, s.contentUnCompressed.length);
			i += sectorSize;
		}
		FileOutputStream out = new FileOutputStream(f);
		out.write(fullFile);
		out.close();
	}

	public byte[] asFileArray() throws IOException {
		byte[] fullFile = new byte[normalSize];
		int i = 0;
		for (Sector s : sectors) {
			System.arraycopy(s.contentUnCompressed, 0, fullFile, i, s.contentUnCompressed.length);
			i += sectorSize;
		}
		return fullFile;
	}

	public class Sector {
		boolean isCompressed = true;
		byte compressionType;
		byte[] contentUnCompressed;
		byte[] contentCompressed;

		public Sector(byte[] in, int sectorSize, int uncomSectorSize, MpqCrypto crypto, int key) throws IOException,
				JMpqException {
			if (crypto != null) {
				in = crypto.decryptBlock(in, key);
			}
			if (sectorSize == uncomSectorSize) {
				contentCompressed = new byte[in.length];
				System.arraycopy(in, 0, contentCompressed, 0, in.length);
				contentUnCompressed = in;
				isCompressed = false;
			} else {
				contentCompressed = new byte[in.length - 1];
				System.arraycopy(in, 1, contentCompressed, 0, in.length - 1);
				compressionType = in[0];
				if (!((compressionType & 2) == 2)) {
					if (((compressionType & 8) == 8))
					throw new JMpqException("Unsupported compression algorithm: " + compressionType);
				}else{
					byte[] temp = new byte[sectorSize];
					System.arraycopy(in, 1, temp, 0, sectorSize - 1);
					contentUnCompressed = JzLibHelper.inflate(temp, uncomSectorSize);
				}
			}			
		}

		public Sector(byte[] data) {
			contentUnCompressed = data;
			try {
				contentCompressed = JzLibHelper.deflate(data);
			} catch (Exception e) {
				contentCompressed = data;
				isCompressed = false;
			}
		}
	}

	public int getSectorSize() {
		return sectorSize;
	}
}
