package systems.crigges.jmpq3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MpqCrypto {
    public static final int MPQ_HASH_TABLE_INDEX = 0;
    public static final int MPQ_HASH_NAME_A = 1;
    public static final int MPQ_HASH_NAME_B = 2;
    public static final int MPQ_HASH_FILE_KEY = 3;
    public static final int MPQ_HASH_KEY2_MIX = 4;
    public static final int MPQ_KEY_HASH_TABLE = -1011927184;
    public static final int MPQ_KEY_BLOCK_TABLE = -326913117;
    private static int[] cryptTable = new int[1280];

    private MpqCrypto() {}
    static {
        int seed = 1048577;
        int index1, index2;

        for (index1 = 0; index1 < 256; index1++) {
            index2 = index1;
            for (int i = 0; i < 5; index2 += 256) {
                seed = (seed * 125 + 3) % 2796203;
                int temp1 = (seed & 0xFFFF) << 16;

                seed = (seed * 125 + 3) % 2796203;
                int temp2 = seed & 0xFFFF;

                cryptTable[index2] = (temp1 | temp2);

                i++;
            }
        }
    }

    public static int hash(String fileName, int hashType) {
        int seed1 = 2146271213;
        int seed2 = -286331154;

        for (int i = 0; i < fileName.length(); i++) {
            char ch = Character.toUpperCase(fileName.charAt(i));
            int index = (hashType << 8) + ch;
            if (index >= cryptTable.length) {
                System.out.println(fileName);
                break;
            }
            seed1 = cryptTable[((hashType << 8) + ch)] ^ seed1 + seed2;
            seed2 = ch + seed1 + seed2 + (seed2 << 5) + 3;
        }

        return seed1;
    }

    public static byte[] decryptBlock(byte[] block, int key) {
        ByteBuffer buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        return decryptBlock(buf, block.length, key);
    }

    public static byte[] decryptBlock(ByteBuffer buf, int length, int key) {
        int seed = -286331154;

        ByteBuffer resultBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);

        length >>= 2;

        for (int i = 0; i < length; i++) {
            seed += cryptTable[(1024 + (key & 0xFF))];

            int ch = buf.getInt() ^ key + seed;
            resultBuffer.putInt(ch);

            key = ((key ^ 0xFFFFFFFF) << 21) + 286331153 | key >>> 11;
            seed = ch + seed + (seed << 5) + 3;
        }

        return resultBuffer.array();
    }

    public static byte[] encryptMpqBlock(ByteBuffer buf, int length, int dwKey1) {
        ByteBuffer resultBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);

        int dwKey2 = -286331154;

        length >>= 2;

        for (int i = 0; i < length; i++) {
            dwKey2 += cryptTable[(1024 + (dwKey1 & 0xFF))];

            int dwValue32 = buf.getInt();
            resultBuffer.putInt(dwValue32 ^ dwKey1 + dwKey2);

            dwKey1 = ((dwKey1 ^ 0xFFFFFFFF) << 21) + 286331153 | dwKey1 >>> 11;
            dwKey2 = dwValue32 + dwKey2 + (dwKey2 << 5) + 3;
        }
        return resultBuffer.array();
    }

    public static byte[] encryptMpqBlock(byte[] bytes, int length, int key) {
        return encryptMpqBlock(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), length, key);
    }
}