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

public class HashTable {
	private MpqCrypto c;
	private MappedByteBuffer hashMap;
	private int hashSize;

	public HashTable(MappedByteBuffer buf) throws IOException {
		this.hashSize = (buf.capacity() / 16);
		this.c = new MpqCrypto();
		byte[] decrypted = this.c.decryptBlock(buf, 16 * this.hashSize, -1011927184);
		File hash = File.createTempFile("block", "crig");
		FileOutputStream hashStream = new FileOutputStream(hash);
		hashStream.write(decrypted);
		hashStream.flush();
		hashStream.close();

		FileChannel hashChannel = FileChannel.open(hash.toPath(),
				new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ });
		this.hashMap = hashChannel.map(FileChannel.MapMode.READ_WRITE, 0L, hashChannel.size());
		this.hashMap.order(ByteOrder.LITTLE_ENDIAN);
	}

	public static void writeNewHashTable(int size, ArrayList<String> names, MappedByteBuffer writeBuffer)
			throws IOException, JMpqException {
		Entry[] content = new Entry[size];
		for (int i = 0; i < size; i++) {
			content[i] = new Entry(-1, -1, -1, -1, -1);
		}
		MpqCrypto c = new MpqCrypto();
		int blockIndex = 0;
		int index;
		int name1;
		int name2;
		for (String s : names) {
			index = c.hash(s, 0);
			name1 = c.hash(s, 1);
			name2 = c.hash(s, 2);
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
		arr = c.encryptMpqBlock(arr, arr.length, -1011927184);
		writeBuffer.put(arr);
	}

	public int getBlockIndexOfFile(String name) throws IOException {
		int index = this.c.hash(name, 0);
		int name1 = this.c.hash(name, 1);
		int name2 = this.c.hash(name, 2);
		int start = index & this.hashSize - 1;
		for (int c = 0; c <= this.hashSize; c++) {
			this.hashMap.position(start * 16);
			Entry cur = new Entry(this.hashMap);
			if ((cur.dwName1 == name1) && (cur.dwName2 == name2))
				return cur.dwBlockIndex;
			if (cur.wPlatform != 0) {
				throw new JMpqException("File Not Found");
			}
			start++;
			start %= this.hashSize;
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
			return "Entry [dwName1=" + this.dwName1 + ",\tdwName2=" + this.dwName2 + ",\tlcLocale=" + this.lcLocale
					+ ",\twPlatform=" + this.wPlatform + ",\tdwBlockIndex=" + this.dwBlockIndex + "]";
		}
	}
}