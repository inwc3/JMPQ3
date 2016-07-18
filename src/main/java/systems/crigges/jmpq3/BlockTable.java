package systems.crigges.jmpq3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

public class BlockTable {
	private MappedByteBuffer blockMap;
	private int size;

	public BlockTable(MappedByteBuffer buf) throws IOException {
		this.size = (buf.capacity() / 16);

		MpqCrypto c = new MpqCrypto();
		byte[] decrypted = c.decryptBlock(buf, this.size * 16, -326913117);
		File block = File.createTempFile("block", "crig");
		FileOutputStream blockStream = new FileOutputStream(block);
		blockStream.write(decrypted);
		blockStream.flush();
		blockStream.close();

		FileChannel blockChannel = FileChannel.open(block.toPath(),
				new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ });
		this.blockMap = blockChannel.map(FileChannel.MapMode.READ_WRITE, 0L, blockChannel.size());
		this.blockMap.order(ByteOrder.LITTLE_ENDIAN);
	}

	public static void writeNewBlocktable(ArrayList<Block> blocks, int size, MappedByteBuffer buf) {
		ByteBuffer temp = ByteBuffer.allocate(size * 16);
		temp.order(ByteOrder.LITTLE_ENDIAN);
		temp.position(0);
		for (Block b : blocks) {
			b.writeToBuffer(temp);
		}
		byte[] arr = temp.array();
		MpqCrypto c = new MpqCrypto();
		arr = c.encryptMpqBlock(arr, arr.length, -326913117);
		buf.put(arr);
	}

	public Block getBlockAtPos(int pos) throws JMpqException {
		if ((pos < 0) || (pos > this.size)) {
			throw new JMpqException("Invaild block position");
		}
		this.blockMap.position(pos * 16);
		try {
			return new Block(this.blockMap);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}

	public ArrayList<Block> getAllVaildBlocks() throws JMpqException {
		ArrayList list = new ArrayList();
		for (int i = 0; i < this.size; i++) {
			Block b = getBlockAtPos(i);
			if ((b.getFlags() & 0x80000000) == -2147483648) {
				list.add(b);
			}
		}
		return list;
	}

	public static class Block {
		private int filePos;
		private int compressedSize;
		private int normalSize;
		private int flags;

		public Block(MappedByteBuffer buf) throws IOException {
			this.filePos = buf.getInt();
			this.compressedSize = buf.getInt();
			this.normalSize = buf.getInt();
			this.flags = buf.getInt();
		}

		public Block(int filePos, int compressedSize, int normalSize, int flags) {
			this.filePos = filePos;
			this.compressedSize = compressedSize;
			this.normalSize = normalSize;
			this.flags = flags;
		}

		public void writeToBuffer(ByteBuffer bb) {
			bb.putInt(this.filePos);
			bb.putInt(this.compressedSize);
			bb.putInt(this.normalSize);
			bb.putInt(this.flags);
		}

		public int getFilePos() {
			return this.filePos;
		}

		public int getCompressedSize() {
			return this.compressedSize;
		}

		public int getNormalSize() {
			return this.normalSize;
		}

		public int getFlags() {
			return this.flags;
		}

		public void setFilePos(int filePos) {
			this.filePos = filePos;
		}

		public void setCompressedSize(int compressedSize) {
			this.compressedSize = compressedSize;
		}

		public void setNormalSize(int normalSize) {
			this.normalSize = normalSize;
		}

		public void setFlags(int flags) {
			this.flags = flags;
		}

		public String toString() {
			return "Block [filePos=" + this.filePos + ", compressedSize=" + this.compressedSize + ", normalSize="
					+ this.normalSize + ", flags=" + this.flags + "]";
		}
	}
}