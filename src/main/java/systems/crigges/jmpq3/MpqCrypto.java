/*
 * 
 */
package systems.crigges.jmpq3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

// TODO: Auto-generated Javadoc
/**
 * The Class MpqCrypto.
 */
public class MpqCrypto {

	/** The Constant MPQ_HASH_TABLE_INDEX. */
	public final static int MPQ_HASH_TABLE_INDEX = 0;
	
	/** The Constant MPQ_HASH_NAME_A. */
	public final static int MPQ_HASH_NAME_A = 1;
	
	/** The Constant MPQ_HASH_NAME_B. */
	public final static int MPQ_HASH_NAME_B = 2;
	
	/** The Constant MPQ_HASH_FILE_KEY. */
	public final static int MPQ_HASH_FILE_KEY = 3;
	
	/** The Constant MPQ_HASH_KEY2_MIX. */
	public final static int MPQ_HASH_KEY2_MIX = 4;

	/** The Constant MPQ_KEY_HASH_TABLE. */
	public final static int MPQ_KEY_HASH_TABLE = 0xC3AF3770; // Obtained by
																// HashString("(hash table)",
																/** The Constant MPQ_KEY_BLOCK_TABLE. */
																// MPQ_HASH_FILE_KEY)
	public final static int MPQ_KEY_BLOCK_TABLE = 0xEC83B3A3; // Obtained by
																// HashString("(block table)",
																// MPQ_HASH_FILE_KEY)

	/** The crypt table. */
																int[] cryptTable = new int[0x500];

	/**
	 * Instantiates a new mpq crypto.
	 */
	public MpqCrypto() {
		prepareCryptTable();
	}

	/**
	 * Prepare crypt table.
	 */
	void prepareCryptTable() {
		int seed = 0x00100001, index1 = 0, index2 = 0, i;

		for (index1 = 0; index1 < 0x100; index1++) {
			for (index2 = index1, i = 0; i < 5; i++, index2 += 0x100) {
				int temp1, temp2;

				seed = (seed * 125 + 3) % 0x2AAAAB;
				temp1 = (seed & 0xFFFF) << 0x10;

				seed = (seed * 125 + 3) % 0x2AAAAB;
				temp2 = (seed & 0xFFFF);

				cryptTable[index2] = (temp1 | temp2);
			}
		}
	}

	/**
	 * This is a port of unsigned long HashString(char *lpszFileName, unsigned
	 * long dwHashType)
	 * 
	 * which is described in http://www.zezula.net/en/mpq/techinfo.html#hashes
	 * 
	 * The implementation there uses 'long' which is a 32 bit int on Windows, so
	 * here we have to use int...
	 *
	 * @param fileName the file name
	 * @param hashType the hash type
	 * @return the int
	 */
	public int hash(String fileName, int hashType) {
		int seed1 = 0x7FED7FED, seed2 = 0xEEEEEEEE;

		for (int i = 0; i < fileName.length(); i++) {
			char ch = Character.toUpperCase(fileName.charAt(i));
			int index = (hashType << 8) + ch;
			if(index >= cryptTable.length){
				System.out.println(fileName);
				break;
			}
			seed1 = (int) (cryptTable[(hashType << 8) + ch] ^ (seed1 + seed2));
			seed2 = ch + seed1 + seed2 + (seed2 << 5) + 3;
		}

		return seed1;
	}

	/**
	 * Decrypt block.
	 *
	 * @param block the block
	 * @param key the key
	 * @return the byte[]
	 */
	public byte[] decryptBlock(byte[] block, int key) {
		ByteBuffer buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
		return decryptBlock(buf, block.length, key);
	}

	/**
	 * Decrypt block.
	 *
	 * @param buf the buf
	 * @param length the length
	 * @param key the key
	 * @return the byte[]
	 */
	public byte[] decryptBlock(ByteBuffer buf, int length, int key) {
		int seed = 0xEEEEEEEE;

		ByteBuffer resultBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);

		// Round to longs
		length >>= 2;

		for (int i = 0; i < length; i++) {
			seed += cryptTable[0x400 + (key & 0xFF)];

			int ch = buf.getInt() ^ (key + seed);
			resultBuffer.putInt(ch);

			key = ((~key << 0x15) + 0x11111111) | (key >>> 0x0B);
			seed = ch + seed + (seed << 5) + 3;
		}

		return resultBuffer.array();
	}

	/**
	 * Encrypt mpq block.
	 *
	 * @param buf the buf
	 * @param length the length
	 * @param dwKey1 the dw key1
	 * @return the byte[]
	 */
	public byte[] encryptMpqBlock(ByteBuffer buf, int length, int dwKey1) {
		ByteBuffer resultBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		int dwValue32;
		int dwKey2 = 0xEEEEEEEE;

		// Round to DWORDs
		length >>= 2;

		// Encrypt the data block at array of DWORDs
		for (int i = 0; i < length; i++) {
			// Modify the second key
			dwKey2 += cryptTable[0x400 + (dwKey1 & 0xFF)];

			dwValue32 = buf.getInt();
			resultBuffer.putInt(dwValue32 ^ (dwKey1 + dwKey2));

			dwKey1 = ((~dwKey1 << 0x15) + 0x11111111) | (dwKey1 >>> 0x0B);
			dwKey2 = dwValue32 + dwKey2 + (dwKey2 << 5) + 3;
		}
		return resultBuffer.array();
	}

	/**
	 * The main method.
	 *
	 * @param args the arguments
	 */
	public static void main(String[] args) {
		MpqCrypto c = new MpqCrypto();

		byte[] bytes = "Hello World!".getBytes();
		byte[] a = c.encryptMpqBlock(ByteBuffer.wrap(bytes), bytes.length, MPQ_KEY_HASH_TABLE);
		byte[] b = c.decryptBlock(ByteBuffer.wrap(a), bytes.length, MPQ_KEY_HASH_TABLE);

		System.out.println("orig = " + Arrays.toString(bytes));
		System.out.println("a = " + Arrays.toString(a));
		System.out.println("b = " + Arrays.toString(b));
		System.out.println("b = " + new String(b));
	}

	/**
	 * Encrypt mpq block.
	 *
	 * @param bytes the bytes
	 * @param length the length
	 * @param key the key
	 * @return the byte[]
	 */
	public byte[] encryptMpqBlock(byte[] bytes, int length, int key) {
		return encryptMpqBlock(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), length, key);
	}

}
