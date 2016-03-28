/*
 * 
 */
package systems.crigges.jmpq3;

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

// TODO: Auto-generated Javadoc
/**
 * The Class BlockTable.
 */
public class BlockTable {
	
	/** The block map. */
	private MappedByteBuffer blockMap;
	
	/** The size. */
	private int size;


	/**
	 * Instantiates a new block table.
	 *
	 * @param buf the buf
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
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

	/**
	 * Write new blocktable.
	 *
	 * @param blocks the blocks
	 * @param size the size
	 * @param buf the buf
	 */
	public static void writeNewBlocktable(ArrayList<Block> blocks, int size, MappedByteBuffer buf) {
		ByteBuffer temp = ByteBuffer.allocate(size * 16);
		temp.order(ByteOrder.LITTLE_ENDIAN);
		temp.position(0);
		for (Block b : blocks) {
			b.writeToBuffer(temp);
		}
		byte[] arr = temp.array();
		MpqCrypto c = new MpqCrypto();
		arr = c.encryptMpqBlock(arr, arr.length, MpqCrypto.MPQ_KEY_BLOCK_TABLE);
		buf.put(arr);
	}


	/**
	 * Gets the block at pos.
	 *
	 * @param pos the pos
	 * @return the block at pos
	 * @throws JMpqException the j mpq exception
	 */
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
	
	/**
	 * Gets the all vaild blocks.
	 *
	 * @return the all vaild blocks
	 * @throws JMpqException the j mpq exception
	 */
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

	/**
	 * The Class Block.
	 */
	public static class Block {
		
		/** The file pos. */
		private int filePos;
		
		/** The compressed size. */
		private int compressedSize;
		
		/** The normal size. */
		private int normalSize;
		
		/** The flags. */
		private int flags;

		/**
		 * Instantiates a new block.
		 *
		 * @param buf the buf
		 * @throws IOException Signals that an I/O exception has occurred.
		 */
		public Block(MappedByteBuffer buf) throws IOException {
			filePos = buf.getInt();
			compressedSize = buf.getInt();
			normalSize = buf.getInt();
			flags = buf.getInt();
		}

		/**
		 * Instantiates a new block.
		 *
		 * @param filePos the file pos
		 * @param compressedSize the compressed size
		 * @param normalSize the normal size
		 * @param flags the flags
		 */
		public Block(int filePos, int compressedSize, int normalSize, int flags) {
			super();
			this.filePos = filePos;
			this.compressedSize = compressedSize;
			this.normalSize = normalSize;
			this.flags = flags;
		}

		/**
		 * Write to buffer.
		 *
		 * @param bb the bb
		 */
		public void writeToBuffer(ByteBuffer bb) {
			bb.putInt(filePos);
			bb.putInt(compressedSize);
			bb.putInt(normalSize);
			bb.putInt(flags);
		}

		/**
		 * Gets the file pos.
		 *
		 * @return the file pos
		 */
		public int getFilePos() {
			return filePos;
		}

		/**
		 * Gets the compressed size.
		 *
		 * @return the compressed size
		 */
		public int getCompressedSize() {
			return compressedSize;
		}

		/**
		 * Gets the normal size.
		 *
		 * @return the normal size
		 */
		public int getNormalSize() {
			return normalSize;
		}

		/**
		 * Gets the flags.
		 *
		 * @return the flags
		 */
		public int getFlags() {
			return flags;
		}

		/**
		 * Sets the file pos.
		 *
		 * @param filePos the new file pos
		 */
		public void setFilePos(int filePos) {
			this.filePos = filePos;
		}

		/**
		 * Sets the compressed size.
		 *
		 * @param compressedSize the new compressed size
		 */
		public void setCompressedSize(int compressedSize) {
			this.compressedSize = compressedSize;
		}

		/**
		 * Sets the normal size.
		 *
		 * @param normalSize the new normal size
		 */
		public void setNormalSize(int normalSize) {
			this.normalSize = normalSize;
		}

		/**
		 * Sets the flags.
		 *
		 * @param flags the new flags
		 */
		public void setFlags(int flags) {
			this.flags = flags;
		}

		
		/* (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			return "Block [filePos=" + filePos + ", compressedSize=" + compressedSize + ", normalSize=" + normalSize
					+ ", flags=" + flags + "]";
		}

	}

}
