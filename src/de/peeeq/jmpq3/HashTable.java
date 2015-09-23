package de.peeeq.jmpq3;

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

public class HashTable {
	private MpqCrypto c;
	private MappedByteBuffer hashMap;
	private int hashSize;

	public HashTable(MappedByteBuffer buf) throws IOException {
		this.hashSize = buf.capacity() / 16;
		c = new MpqCrypto();
		byte[] decrypted = c.decryptBlock(buf, 16 * hashSize, MpqCrypto.MPQ_KEY_HASH_TABLE);
		File hash = File.createTempFile("block", "crig");
		FileOutputStream hashStream = new FileOutputStream(hash);
		hashStream.write(decrypted);
		hashStream.flush();
		hashStream.close();
		
		FileChannel hashChannel = FileChannel.open(hash.toPath(), StandardOpenOption.CREATE, 
					StandardOpenOption.WRITE, StandardOpenOption.READ);
		hashMap = hashChannel.map(MapMode.READ_WRITE, 0, hashChannel.size());
		hashMap.order(ByteOrder.LITTLE_ENDIAN);
	}

	public static void writeNewHashTable(int size, ArrayList<String> names, MappedByteBuffer writeBuffer) throws IOException, JMpqException {
		Entry[] content = new Entry[size];
		for (int i = 0; i < size; i++) {
			content[i] = new Entry(-1, -1, -1, -1, -1);
		}
		MpqCrypto c = new MpqCrypto();
		int blockIndex = 0;
		for (String s : names) {
			int index = c.hash(s, MpqCrypto.MPQ_HASH_TABLE_INDEX);
			int name1 = c.hash(s, MpqCrypto.MPQ_HASH_NAME_A); 
			int name2 = c.hash(s, MpqCrypto.MPQ_HASH_NAME_B);
			int start = index & (size - 1);
			while (true) {
				if (content[start].wPlatform == -1) {
					content[start] = new Entry(name1, name2, 0, 0, blockIndex);
					break;
				}
				start++;
				start = start % size;
			}
			blockIndex++;
		}
		ByteBuffer temp = ByteBuffer.allocate(size * 16);
		temp.order(ByteOrder.LITTLE_ENDIAN);
		temp.position(0);
		for (Entry e : content) {
			e.writeToBuffer(temp);
		}
		byte[] arr = temp.array();
		arr = c.encryptMpqBlock(arr, arr.length, MpqCrypto.MPQ_KEY_HASH_TABLE);
		writeBuffer.put(arr);
	}

	public int getBlockIndexOfFile(String name) throws IOException {
		int index = c.hash(name, MpqCrypto.MPQ_HASH_TABLE_INDEX);
		int name1 = c.hash(name, MpqCrypto.MPQ_HASH_NAME_A);
		int name2 = c.hash(name, MpqCrypto.MPQ_HASH_NAME_B);
		int start = index & (hashSize - 1);
		for (int c = 0; c <= hashSize; c++) {
			hashMap.position(start * 16);
			Entry cur = new Entry(hashMap);
			if (cur.dwName1 == name1 && cur.dwName2 == name2) {
				return cur.dwBlockIndex;
			} else if (cur.wPlatform != 0) {
				throw new JMpqException("File Not Found");
			}
			start++;
			start %= hashSize;
		}
		throw new JMpqException("File Not Found");
	}

	public static class Entry {
		private int dwName1;
		private int dwName2;
		private int lcLocale;
		private int wPlatform;
		private int dwBlockIndex;

		public Entry(int dwName1, int dwName2, int lcLocale, int wPlatform, int dwBlockIndex) {
			this.dwName1 = dwName1;
			this.dwName2 = dwName2;
			this.lcLocale = lcLocale;
			this.wPlatform = wPlatform;
			this.dwBlockIndex = dwBlockIndex;
		}

		public Entry(MappedByteBuffer in) throws IOException {
			this.dwName1 = in.getInt();
			this.dwName2 = in.getInt();
			this.lcLocale = in.getShort();
			this.wPlatform = in.getShort();
			this.dwBlockIndex = in.getInt();
		}

		public void writeToBuffer(ByteBuffer bb) {
			bb.putInt(dwName1);
			bb.putInt(dwName2);
			bb.putShort((short) lcLocale);
			bb.putShort((short) wPlatform);
			bb.putInt(dwBlockIndex);
		}
		
		public void setBlockIndex(int index){
			dwBlockIndex = index;
		}

		@Override
		public String toString() {
			return "Entry [dwName1=" + dwName1 + ",	dwName2=" + dwName2 + ",	lcLocale=" + lcLocale + ",	wPlatform="
					+ wPlatform + ",	dwBlockIndex=" + dwBlockIndex + "]";
		}
	}
}
