/*
 * 
 */
 package systems.crigges.jmpq3;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;

import javax.management.remote.JMXProviderException;

import systems.crigges.jmpq3.BlockTable.Block;

// TODO: Auto-generated Javadoc
/**
 * The Class MpqFile.
 */
public class MpqFile {
	
	/** The Constant COMPRESSED. */
	public static final int COMPRESSED = 0x00000200;
	
	/** The Constant ENCRYPTED. */
	public static final int ENCRYPTED = 0x00010000;
	
	/** The Constant SINGLEUNIT. */
	public static final int SINGLEUNIT = 0x01000000;
	
	/** The Constant ADJUSTED_ENCRYPTED. */
	public static final int ADJUSTED_ENCRYPTED = 0x00020000;
	
	/** The Constant EXISTS. */
	public static final int EXISTS = 0x80000000;
	
	/** The Constant DELETED. */
	public static final int DELETED = 0x02000000;
	
	/** The buf. */
	private MappedByteBuffer buf;
	
	/** The block. */
	private Block block;
	
	/** The crypto. */
	private MpqCrypto crypto = null;
	
	/** The sector size. */
	private int sectorSize;
	
	/** The offset. */
	private int offset;
	
	/** The comp size. */
	private int compSize;
	
	/** The normal size. */
	private int normalSize;
	
	/** The flags. */
	private int flags;
	
	/** The block index. */
	private int blockIndex;
	
	/** The name. */
	private String name;
	
	/** The sector count. */
	private int sectorCount;
	
	/** The base key. */
	private int baseKey;
	
	/** The sep index. */
	private int sepIndex;

	/**
	 * Gets the block index.
	 *
	 * @return the block index
	 */
	public int getBlockIndex() {
		return blockIndex;
	}

	/**
	 * Sets the block index.
	 *
	 * @param blockIndex the new block index
	 */
	public void setBlockIndex(int blockIndex) {
		this.blockIndex = blockIndex;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "MpqFile [sectorSize=" + sectorSize + ", offset=" + offset + ", compSize=" + compSize + ", normalSize="
				+ normalSize + ", flags=" + flags + ", blockIndex=" + blockIndex + ", name=" + name + "]";
	}

	/**
	 * Sets the offset.
	 *
	 * @param newOffset the new offset
	 */
	public void setOffset(int newOffset) {
		offset = newOffset;
	}

	/**
	 * Instantiates a new mpq file.
	 *
	 * @param buf the buf
	 * @param b the b
	 * @param sectorSize the sector size
	 * @param name the name
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws JMpqException the j mpq exception
	 */
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
	
	
	
	/**
	 * Gets the offset.
	 *
	 * @return the offset
	 */
	public int getOffset() {
		return offset;
	}

	/**
	 * Gets the comp size.
	 *
	 * @return the comp size
	 */
	public int getCompSize() {
		return compSize;
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
	 * Gets the name.
	 *
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * Extract to file.
	 *
	 * @param f the f
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void extractToFile(File f) throws IOException {
		if(sectorCount == 1){
			f.createNewFile();
		}
		extractToOutputStream(new FileOutputStream(f));
	}

	
	/**
	 * Extract to output stream.
	 *
	 * @param writer the writer
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void extractToOutputStream(OutputStream writer) throws IOException {
		if(sectorCount == 1){
			writer.close();
			return;
		}
		if((block.getFlags() & SINGLEUNIT) == SINGLEUNIT) {
			if((block.getFlags() & COMPRESSED) == COMPRESSED) {
				buf.position(block.getFilePos());
				byte[] arr = getSectorAsByteArray(buf, compSize);
				if(crypto != null){
					arr = crypto.decryptBlock(arr, baseKey);
				}
				arr = decompressSector(arr, block.getCompressedSize(), block.getNormalSize());
				writer.write(arr);
				writer.flush();
				writer.close();
			}else{
				buf.position(block.getFilePos());
				byte[] arr = getSectorAsByteArray(buf, compSize);
				if(crypto != null){
					arr = crypto.decryptBlock(arr, baseKey);
				}
				writer.write(arr);
				writer.flush();
				writer.close();
			}
			return;
		}
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
	
	/**
	 * Write file and block.
	 *
	 * @param newBlock the new block
	 * @param writeBuffer the write buffer
	 */
	public void writeFileAndBlock(Block newBlock,  MappedByteBuffer writeBuffer){
		newBlock.setNormalSize(normalSize);
		newBlock.setCompressedSize(compSize);
		if(normalSize == 0){
			newBlock.setFlags(block.getFlags());
			return;
		}
		if ((block.getFlags() & SINGLEUNIT) == SINGLEUNIT) {
			if ((block.getFlags() & ENCRYPTED) == ENCRYPTED) {
				buf.position(block.getFilePos());
				byte[] arr = getSectorAsByteArray(buf, compSize);
				arr = crypto.decryptBlock(arr, baseKey);
				writeBuffer.put(arr);
			}
			if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
				newBlock.setFlags(EXISTS | SINGLEUNIT | COMPRESSED);
			}else{;
				newBlock.setFlags(EXISTS | SINGLEUNIT);
			}
		}else{
			ByteBuffer sotBuffer = null;
			buf.position(block.getFilePos());
			byte[] sot = new byte[sectorCount * 4];
			buf.get(sot);
			if (crypto != null) {
				sot = crypto.decryptBlock(sot, baseKey - 1);
			}
			writeBuffer.put(sot);
			sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
			int start = sotBuffer.getInt();
			int end = sotBuffer.getInt();
			for (int i = 0; i < sectorCount - 1; i++) {
				buf.position(block.getFilePos() + start);
				byte[] arr = getSectorAsByteArray(buf, end - start);
				if(crypto != null){
					arr = crypto.decryptBlock(arr, baseKey + i);
				}
				writeBuffer.put(arr);

				start = end;
				try {
					end = sotBuffer.getInt();
				} catch (BufferUnderflowException e) {
					break;
				}
			}
			if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
				newBlock.setFlags(EXISTS | COMPRESSED);
			}else{;
				newBlock.setFlags(EXISTS);
			}
		}
	}
	
	/**
	 * Write file and block.
	 *
	 * @param f the f
	 * @param b the b
	 * @param buf the buf
	 * @param sectorSize the sector size
	 */
	public static void writeFileAndBlock(File f, Block b, MappedByteBuffer buf, int sectorSize){
		try {
			writeFileAndBlock(Files.readAllBytes(f.toPath()), b, buf, sectorSize);
		} catch (IOException e) {
			throw new RuntimeException("Internal JMpq Error", e);
		}
		
	}
	
	/**
	 * Write file and block.
	 *
	 * @param fileArr the file arr
	 * @param b the b
	 * @param buf the buf
	 * @param sectorSize the sector size
	 */
	public static void writeFileAndBlock(byte[] fileArr, Block b, MappedByteBuffer buf, int sectorSize){
		ByteBuffer fileBuf = ByteBuffer.wrap(fileArr);
		fileBuf.position(0);
		b.setNormalSize(fileArr.length);
		b.setFlags(EXISTS | COMPRESSED);
		int sectorCount = (int) (Math.ceil(((double) fileArr.length / (double) sectorSize)) + 1);
		ByteBuffer sot = ByteBuffer.allocate(sectorCount * 4);
		sot.order(ByteOrder.LITTLE_ENDIAN);
		sot.position(0);
		sot.putInt(sectorCount * 4);
		buf.position(sectorCount * 4);
		int sotPos = sectorCount * 4;
		byte[] temp = new byte[sectorSize];
		for(int i = 1; i <= sectorCount - 1; i++){
			if(fileBuf.position() + sectorSize > fileArr.length){
				temp = new byte[fileArr.length - fileBuf.position()];
			}
			fileBuf.get(temp);
			byte[] compSector = null;
			try{
				compSector = JzLibHelper.deflate(temp);
			}catch(ArrayIndexOutOfBoundsException e){
				compSector = null;
			}
			if(compSector != null && compSector.length < temp.length){
				buf.put((byte) 2);
				buf.put(compSector);
				sotPos += compSector.length + 1;
			}else{
				buf.put(temp);
				sotPos += temp.length;
			}
			sot.putInt(sotPos);
		}
		b.setCompressedSize(sotPos );
		buf.position(0);
		sot.position(0);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.put(sot);
	}
	
	
	/**
	 * Gets the sector as byte array.
	 *
	 * @param buf the buf
	 * @param sectorSize the sector size
	 * @return the sector as byte array
	 */
	private byte[] getSectorAsByteArray(MappedByteBuffer buf, int sectorSize){
		byte[] arr = new byte[sectorSize];
		buf.get(arr);
		return arr;
	}
	
	/**
	 * Decompress sector.
	 *
	 * @param sector the sector
	 * @param normalSize the normal size
	 * @param uncompSize the uncomp size
	 * @return the byte[]
	 * @throws JMpqException the j mpq exception
	 */
	private byte[] decompressSector(byte[] sector, int normalSize, int uncompSize) throws JMpqException{
		if(normalSize == uncompSize){
			return sector;
		}else{
			byte compressionType = sector[0];
			if (((compressionType & 2) == 2)){
				return JzLibHelper.inflate(sector, 1 ,uncompSize);
			}
			throw new JMpqException("Unsupported compression algorithm");
		}
	}
}
