package de.peeeq.jmpq;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedList;

public class BlockTable {
	private MappedByteBuffer blockMap;
	private int size;

	public BlockTable(MappedByteBuffer buf) throws IOException {
		size = buf.capacity() / 16;
		
		MpqCrypto c = new MpqCrypto();
		byte[] decrypted = c.decryptBlock(buf, size * 16, MpqCrypto.MPQ_KEY_BLOCK_TABLE);
		File block = File.createTempFile("block", "crig");
		FileOutputStream blockStream = new FileOutputStream(block);
		blockStream.write(decrypted);
		blockStream.flush();
		blockStream.close();
		
		FileChannel blockChannel = FileChannel.open(block.toPath(), StandardOpenOption.CREATE, 
					StandardOpenOption.WRITE, StandardOpenOption.READ);
		blockMap = blockChannel.map(MapMode.READ_WRITE, 0, blockChannel.size());
		blockMap.order(ByteOrder.LITTLE_ENDIAN);
		
	}

	public BlockTable(LinkedList<MpqFile> files, int size) {
//		content = new Block[size];
//		int c = 0;
//		for (MpqFile f : files) {
//			content[c] = new Block(f.getOffset(), f.getCompSize(), f.getNormalSize(), MpqFile.COMPRESSED
//					| MpqFile.EXISTS);
//			ht.put(f, content[c]);
//			f.setBlockIndex(c);
//			c++;
//		}
//		while (c < size) {
//			content[c] = new Block(0, 0, 0, 0);
//			c++;
//		}
	}

	public void writeToFile(FileOutputStream out) throws IOException {
//		byte[] temp = new byte[content.length * 16];
//		int i = 0;
//		for (Block b : content) {
//			System.arraycopy(b.asByteArray(), 0, temp, i * 16, 16);
//			i++;
//		}
//		MpqCrypto crypt = new MpqCrypto();
//		temp = crypt.encryptMpqBlock(temp, temp.length, MpqCrypto.MPQ_KEY_BLOCK_TABLE);
//		out.write(temp);
	}

	public Block getBlockAtPos(int pos) throws JMpqException {
		if(pos < 0 || pos > size){
			throw new JMpqException("Invaild block position");
		}else{
			blockMap.position(pos * 16);
			try {
				return new Block(blockMap);
			} catch (IOException e) {
				throw new JMpqException(e);
			}
		}
		
	}
	
	public ArrayList<Block> getAllVaildBlocks() throws JMpqException{
		ArrayList<Block> list = new ArrayList<>();
		for(int i = 0; i < size; i++){
			Block b = getBlockAtPos(i);
			if((b.getFlags() & MpqFile.EXISTS) == MpqFile.EXISTS){
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
			filePos = buf.getInt();
			compressedSize = buf.getInt();
			normalSize = buf.getInt();
			flags = buf.getInt();
		}

		public Block(int filePos, int compressedSize, int normalSize, int flags) {
			super();
			this.filePos = filePos;
			this.compressedSize = compressedSize;
			this.normalSize = normalSize;
			this.flags = flags;
		}

		public void writeToBuffer(ByteBuffer bb) {
			bb.putInt(filePos);
			bb.putInt(compressedSize);
			bb.putInt(normalSize);
			bb.putInt(flags);
		}

		public int getFilePos() {
			return filePos;
		}

		public int getCompressedSize() {
			return compressedSize;
		}

		public int getNormalSize() {
			return normalSize;
		}

		public long getFlags() {
			return flags;
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

		
		@Override
		public String toString() {
			return "Block [filePos=" + filePos + ", compressedSize=" + compressedSize + ", normalSize=" + normalSize
					+ ", flags=" + flags + "]";
		}

	}

}
