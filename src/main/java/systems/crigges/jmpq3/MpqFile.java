
package systems.crigges.jmpq3;

import com.esotericsoftware.minlog.Log;
import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.JzLibHelper;

import java.io.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;

public class MpqFile {
    public static final int COMPRESSED = 0x00000200;
    public static final int ENCRYPTED = 0x00010000;
    public static final int SINGLE_UNIT = 0x01000000;
    public static final int ADJUSTED_ENCRYPTED = 0x00020000;
    public static final int EXISTS = 0x80000000;
    public static final int DELETED = 0x02000000;

    private ByteBuffer buf;
    private Block block;
    private String name;
    private boolean isEncrypted = false;
    private int sectorSize;
    private int compressedSize;
    private int normalSize;
    private int flags;
    private int sectorCount;
    private int baseKey;

    public MpqFile(ByteBuffer buf, Block b, int sectorSize, String name) throws IOException, JMpqException {
        this.buf = buf;
        this.block = b;
        this.sectorSize = sectorSize;
        this.name = name;
        this.compressedSize = b.getCompressedSize();
        this.normalSize = b.getNormalSize();
        this.flags = b.getFlags();
        this.sectorCount = (int) (Math.ceil(((double) normalSize / (double) sectorSize)) + 1);
        this.baseKey = 0;
        int sepIndex = name.lastIndexOf('\\');
        String pathlessName = name.substring(sepIndex + 1);
        if (b.hasFlag(ENCRYPTED)) {
            isEncrypted = true;
            baseKey = MpqCrypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
            if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                baseKey = ((baseKey + b.getFilePos()) ^ b.getNormalSize());
            }
        }
    }

    public int getFlags() {
        return flags;
    }

    public String getName() {
        return name;
    }

    public void extractToFile(File f) throws IOException {
        if (sectorCount == 1) {
            f.createNewFile();
        }
        extractToOutputStream(new FileOutputStream(f));
    }

    public byte[] extractToBytes() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        extractToOutputStream(byteArrayOutputStream);
        byte[] bytes = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return bytes;
    }

    public void extractToOutputStream(OutputStream writer) throws IOException {
        if (sectorCount == 1) {
            writer.close();
            return;
        }
        if (block.hasFlag(SINGLE_UNIT)) {
            if (block.hasFlag(COMPRESSED)) {
                buf.position(0);
                byte[] arr = getSectorAsByteArray(buf, compressedSize);
                if (isEncrypted) {
                    arr = MpqCrypto.decryptBlock(arr, baseKey);
                }
                arr = decompressSector(arr, block.getCompressedSize(), block.getNormalSize());
                writer.write(arr);
                writer.flush();
                writer.close();
            } else {
                check(writer);
            }
            return;
        }
        if (block.hasFlag(COMPRESSED)) {
            ByteBuffer sotBuffer = null;
            buf.position(0);
            byte[] sot = new byte[sectorCount * 4];
            buf.get(sot);
            if (isEncrypted) {
                sot = MpqCrypto.decryptBlock(sot, baseKey - 1);
            }
            sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
            int start = sotBuffer.getInt();
            int end = sotBuffer.getInt();
            int finalSize = 0;
            for (int i = 0; i < sectorCount - 1; i++) {
                buf.position(0 + start);
                byte[] arr = getSectorAsByteArray(buf, end - start);
                if (isEncrypted) {
                    arr = MpqCrypto.decryptBlock(arr, baseKey + i);
                }
                if (block.getNormalSize() - finalSize <= sectorSize) {
                    arr = decompressSector(arr, end - start, block.getNormalSize() - finalSize);
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
            check(writer);
        }
    }

    private void check(OutputStream writer) throws IOException {
        buf.position(0);
        byte[] arr = getSectorAsByteArray(buf, compressedSize);
        if (isEncrypted) {
            arr = MpqCrypto.decryptBlock(arr, baseKey);
        }
        writer.write(arr);
        writer.flush();
        writer.close();
    }

    /**
     * Write file and block.
     *
     * @param newBlock
     *            the new block
     * @param writeBuffer
     *            the write buffer
     */
    public void writeFileAndBlock(Block newBlock, MappedByteBuffer writeBuffer) throws JMpqException {
        newBlock.setNormalSize(normalSize);
        newBlock.setCompressedSize(compressedSize);
        if (normalSize == 0) {
            newBlock.setFlags(block.getFlags());
            return;
        }
        if ((block.hasFlag(SINGLE_UNIT)) || (!block.hasFlag(COMPRESSED))) {
            buf.position(0);
            byte[] arr = getSectorAsByteArray(buf, block.hasFlag(COMPRESSED) ? compressedSize : normalSize);
            if ((block.getFlags() & ENCRYPTED) == ENCRYPTED) {
                if (block.hasFlag(ADJUSTED_ENCRYPTED)) {
                    throw new JMpqException("fucvk");
                }
                arr = MpqCrypto.decryptBlock(arr, baseKey);
            }
            writeBuffer.put(arr);

            if (block.hasFlag(SINGLE_UNIT)) {
                Log.info("singleunit detected");
                if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
                    newBlock.setFlags(EXISTS | SINGLE_UNIT | COMPRESSED);
                } else {
                    newBlock.setFlags(EXISTS | SINGLE_UNIT);
                }
            } else {
                if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
                    newBlock.setFlags(EXISTS | COMPRESSED);
                } else {
                    newBlock.setFlags(EXISTS);
                }
            }
        } else {
            buf.position(0);
            byte[] sot = new byte[sectorCount * 4];
            buf.get(sot);
            if (isEncrypted) {
                sot = MpqCrypto.decryptBlock(sot, baseKey - 1);
            }
            writeBuffer.put(sot);
            ByteBuffer sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
            int start = sotBuffer.getInt();
            int end = sotBuffer.getInt();
            for (int i = 0; i < sectorCount - 1; i++) {
                buf.position(0 + start);
                byte[] arr = getSectorAsByteArray(buf, end - start);
                if (isEncrypted) {
                    arr = MpqCrypto.decryptBlock(arr, baseKey + i);
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
            } else {
                newBlock.setFlags(EXISTS);
            }
        }
    }

    /**
     * Write file and block.
     *
     * @param f
     *            the f
     * @param b
     *            the b
     * @param buf
     *            the buf
     * @param sectorSize
     *            the sector size
     */
    public static void writeFileAndBlock(File f, Block b, MappedByteBuffer buf, int sectorSize) {
        try {
            writeFileAndBlock(Files.readAllBytes(f.toPath()), b, buf, sectorSize, "");
        } catch (IOException e) {
            throw new RuntimeException("Internal JMpq Error", e);
        }
    }

    /**
     * Write file and block.
     *
     * @param fileArr
     *            the file arr
     * @param b
     *            the b
     * @param buf
     *            the buf
     * @param sectorSize
     *            the sector size
     */
    public static void writeFileAndBlock(byte[] fileArr, Block b, MappedByteBuffer buf, int sectorSize, String pathlessName) {
        ByteBuffer fileBuf = ByteBuffer.wrap(fileArr);
        fileBuf.position(0);
        b.setNormalSize(fileArr.length);
        if (b.getFlags() == 0) {
            if(fileArr.length > 0) {
                b.setFlags(EXISTS | COMPRESSED);
            } else {
                b.setFlags(EXISTS);
                return;
            }
        }
        int sectorCount = (int) (Math.ceil(((double) fileArr.length / (double) sectorSize)) + 1);
        ByteBuffer sot = ByteBuffer.allocate(sectorCount * 4);
        sot.order(ByteOrder.LITTLE_ENDIAN);
        sot.position(0);
        sot.putInt(sectorCount * 4);
        buf.position(sectorCount * 4);
        int sotPos = sectorCount * 4;
        byte[] temp = new byte[sectorSize];
        for (int i = 0; i < sectorCount - 1; i++) {
            if (fileBuf.position() + sectorSize > fileArr.length) {
                temp = new byte[fileArr.length - fileBuf.position()];
            }
            fileBuf.get(temp);
            byte[] compSector = null;
            try {
                compSector = JzLibHelper.deflate(temp);
            } catch (ArrayIndexOutOfBoundsException e) {
                compSector = null;
            }
            if (compSector != null && compSector.length < temp.length) {
                if (b.hasFlag(ENCRYPTED)) {
                    int bKey = MpqCrypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
                    if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                        bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
                    }
                    byte[] encryptedSector = MpqCrypto.encryptMpqBlock(DebugHelper.appendData((byte) 2, compSector), compSector.length + 1, bKey + (i));
                    buf.put(encryptedSector);
                } else {
                    // deflate compression indicator
                    buf.put((byte) 2);
                    buf.put(compSector);
                }
                sotPos += compSector.length + 1;
            } else {
                if (b.hasFlag(ENCRYPTED)) {
                    int bKey = MpqCrypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
                    if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                        bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
                    }
                    byte[] encryptedSector = MpqCrypto.encryptMpqBlock(temp, temp.length, bKey + (i));
                    buf.put(encryptedSector);
                } else {
                    buf.put(temp);
                }
                sotPos += temp.length;
            }
            sot.putInt(sotPos);
        }
        b.setCompressedSize(sotPos);
        buf.position(0);
        sot.position(0);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        if (b.hasFlag(ENCRYPTED)) {
            int bKey = MpqCrypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
            if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
            }
            byte[] encryptedSector = MpqCrypto.encryptMpqBlock(sot, sot.array().length, bKey - 1);
            buf.put(encryptedSector);
        } else {
            buf.put(sot);
        }
    }

    /**
     * Gets the sector as byte array.
     *
     * @param buf
     *            the buf
     * @param sectorSize
     *            the sector size
     * @return the sector as byte array
     */
    private byte[] getSectorAsByteArray(ByteBuffer buf, int sectorSize) {
        byte[] arr = new byte[sectorSize];
        buf.get(arr);
        return arr;
    }

    /**
     * Decompress sector.
     *
     * @param sector
     *            the sector
     * @param normalSize
     *            the normal size
     * @param uncompressedSize
     *            the uncomp size
     * @return the byte[]
     * @throws JMpqException
     *             the j mpq exception
     */
    private byte[] decompressSector(byte[] sector, int normalSize, int uncompressedSize) throws JMpqException {
        return CompressionUtil.decompress(sector, normalSize, uncompressedSize);
    }

    @Override
    public String toString() {
        return "MpqFile [sectorSize=" + sectorSize + ", compressedSize=" + compressedSize + ", normalSize=" + normalSize + ", flags=" + flags + ", name=" + name
                + "]";
    }
}
