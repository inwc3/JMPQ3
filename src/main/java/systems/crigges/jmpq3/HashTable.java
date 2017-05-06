package systems.crigges.jmpq3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;

import static java.nio.file.StandardOpenOption.*;

public class HashTable {
	private MappedByteBuffer hashMap;
	private int hashSize;
	private HashMap<String, Integer> positionCache = new HashMap<>();

	public HashTable(ByteBuffer buf) throws IOException {
		this.hashSize = (buf.capacity() / 16);
		byte[] decrypted = MpqCrypto.decryptBlock(buf, 16 * this.hashSize, -1011927184);
		File hash = File.createTempFile("block", "crig", JMpqEditor.tempDir);
		hash.deleteOnExit();
		try (FileOutputStream hashStream = new FileOutputStream(hash); FileChannel hashChannel = FileChannel.open(hash.toPath(), CREATE, WRITE, READ)) {
			hashStream.write(decrypted);
			hashStream.flush();
        	this.hashMap = hashChannel.map(FileChannel.MapMode.READ_WRITE, 0L, hashChannel.size());
			this.hashMap.order(ByteOrder.LITTLE_ENDIAN);
		}

	}

	public static void writeNewHashTable(int size, ArrayList<String> names, MappedByteBuffer writeBuffer) throws IOException {
		Entry[] content = new Entry[size];
		for (int i = 0; i < size; i++) {
			content[i] = new Entry(-1, -1, -1, -1, -1);
		}
		int blockIndex = 0;
		int index;
		int name1;
		int name2;
		for (String s : names) {
			index = MpqCrypto.hash(s, 0);
			name1 = MpqCrypto.hash(s, 1);
			name2 = MpqCrypto.hash(s, 2);
			int start = index & size - 1;
			while (true) {
				if (content[start].wPlatform == -1) {
					content[start] = new Entry(name1, name2, 0, 0, blockIndex);
					break;
				}
				start++;
				start %= size;
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
		arr = MpqCrypto.encryptMpqBlock(arr, arr.length, -1011927184);
		writeBuffer.put(arr);
	}

	public int getBlockIndexOfFile(String name) throws IOException {
		if (!positionCache.containsKey(name)) {
			int index = MpqCrypto.hash(name, 0);
			int name1 = MpqCrypto.hash(name, 1);
			int name2 = MpqCrypto.hash(name, 2);
			int mask = this.hashSize - 1;
			int start = index & mask;
			for (int c = 0; c <= this.hashSize; c++) {
				this.hashMap.position(start * 16);
				Entry cur = new Entry(this.hashMap);
				if (cur.dwBlockIndex == 0xFFFFFFFF)
					break;
				if ((cur.dwName1 == name1) && (cur.dwName2 == name2)) {
					positionCache.put(name, cur.dwBlockIndex);
					return positionCache.get(name);
				}
				start = (start + 1) & mask;
			}
			throw new JMpqException("File Not Found <" + name + ">");
		}
		return positionCache.get(name);
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
			bb.putInt(this.dwName1);
			bb.putInt(this.dwName2);
			bb.putShort((short) this.lcLocale);
			bb.putShort((short) this.wPlatform);
			bb.putInt(this.dwBlockIndex);
		}

		public void setBlockIndex(int index) {
			this.dwBlockIndex = index;
		}

		public String toString() {
			return "Entry [dwName1=" + this.dwName1 + ",\tdwName2=" + this.dwName2 + ",\tlcLocale=" + this.lcLocale + ",\twPlatform=" + this.wPlatform
					+ ",\tdwBlockIndex=" + this.dwBlockIndex + "]";
		}
	}
}