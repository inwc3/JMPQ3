
package systems.crigges.jmpq3;


import systems.crigges.jmpq3.BlockTable.Block;

import java.io.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;


public class MpqFile {

    public static final int COMPRESSED = 0x00000200;
    public static final int ENCRYPTED = 0x00010000;
    public static final int SINGLEUNIT = 0x01000000;
    public static final int ADJUSTED_ENCRYPTED = 0x00020000;
    public static final int EXISTS = 0x80000000;
    public static final int DELETED = 0x02000000;
    private MappedByteBuffer buf;
    private Block block;
    private boolean isEncrypted = false;
    private int sectorSize;
    private int offset;
    private int compSize;
    private int normalSize;
    private int flags;
    private int blockIndex;
    private String name;
    private int sectorCount;
    private int baseKey;
    private int sepIndex;


    public int getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(int blockIndex) {
        this.blockIndex = blockIndex;
    }

    @Override
    public String toString() {
        return "MpqFile [sectorSize=" + sectorSize + ", offset=" + offset + ", compSize=" + compSize + ", normalSize="
                + normalSize + ", flags=" + flags + ", blockIndex=" + blockIndex + ", name=" + name + "]";
    }

    public void setOffset(int newOffset) {
        offset = newOffset;
    }

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
            isEncrypted = true;
            baseKey = MpqCrypto.hash(pathlessName, MpqCrypto.MPQ_HASH_FILE_KEY);
            if ((b.getFlags() & ADJUSTED_ENCRYPTED) == ADJUSTED_ENCRYPTED) {
                baseKey = ((baseKey + b.getFilePos()) ^ b.getNormalSize());
            }
        }
    }

    public int getOffset() {
        return offset;
    }

    public int getCompSize() {
        return compSize;
    }

    public int getNormalSize() {
        return normalSize;
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
        if ((block.getFlags() & SINGLEUNIT) == SINGLEUNIT) {
            if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
                buf.position(block.getFilePos());
                byte[] arr = getSectorAsByteArray(buf, compSize);
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
        if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
            ByteBuffer sotBuffer = null;
            buf.position(block.getFilePos());
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
                buf.position(block.getFilePos() + start);
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
        buf.position(block.getFilePos());
        byte[] arr = getSectorAsByteArray(buf, compSize);
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
     * @param newBlock    the new block
     * @param writeBuffer the write buffer
     */
    public void writeFileAndBlock(Block newBlock, MappedByteBuffer writeBuffer) {
        newBlock.setNormalSize(normalSize);
        newBlock.setCompressedSize(compSize);
        if (normalSize == 0) {
            newBlock.setFlags(block.getFlags());
            return;
        }
        if (((block.getFlags() & SINGLEUNIT) == SINGLEUNIT) || (block.getFlags() & COMPRESSED) != COMPRESSED) {
            if ((block.getFlags() & ENCRYPTED) == ENCRYPTED) {
                buf.position(block.getFilePos());
                byte[] arr = getSectorAsByteArray(buf, compSize);
                arr = MpqCrypto.decryptBlock(arr, baseKey);
                writeBuffer.put(arr);
            }
            if ((block.getFlags() & COMPRESSED) == COMPRESSED) {
                newBlock.setFlags(EXISTS | SINGLEUNIT | COMPRESSED);
            } else {
                newBlock.setFlags(EXISTS | SINGLEUNIT);
            }
        } else {
            buf.position(block.getFilePos());
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
                buf.position(block.getFilePos() + start);
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
     * @param f          the f
     * @param b          the b
     * @param buf        the buf
     * @param sectorSize the sector size
     */
    public static void writeFileAndBlock(File f, Block b, MappedByteBuffer buf, int sectorSize) {
        try {
            writeFileAndBlock(Files.readAllBytes(f.toPath()), b, buf, sectorSize);
        } catch (IOException e) {
            throw new RuntimeException("Internal JMpq Error", e);
        }
    }

    /**
     * Write file and block.
     *
     * @param fileArr    the file arr
     * @param b          the b
     * @param buf        the buf
     * @param sectorSize the sector size
     */
    public static void writeFileAndBlock(byte[] fileArr, Block b, MappedByteBuffer buf, int sectorSize) {
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
        for (int i = 1; i <= sectorCount - 1; i++) {
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
                buf.put((byte) 2);
                buf.put(compSector);
                sotPos += compSector.length + 1;
            } else {
                buf.put(temp);
                sotPos += temp.length;
            }
            sot.putInt(sotPos);
        }
        b.setCompressedSize(sotPos);
        buf.position(0);
        sot.position(0);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(sot);
    }


    /**
     * Gets the sector as byte array.
     *
     * @param buf        the buf
     * @param sectorSize the sector size
     * @return the sector as byte array
     */
    private byte[] getSectorAsByteArray(MappedByteBuffer buf, int sectorSize) {
        byte[] arr = new byte[sectorSize];
        buf.get(arr);
        return arr;
    }

    /**
     * Decompress sector.
     *
     * @param sector     the sector
     * @param normalSize the normal size
     * @param uncompSize the uncomp size
     * @return the byte[]
     * @throws JMpqException the j mpq exception
     */
    private byte[] decompressSector(byte[] sector, int normalSize, int uncompSize) throws JMpqException {
        if (normalSize == uncompSize) {
            return sector;
        } else {
            byte compressionType = sector[0];
            if (((compressionType & 2) == 2)) {
                return JzLibHelper.inflate(sector, 1, uncompSize);
            }
            throw new JMpqException("Unsupported compression algorithm");
        }
    }
}
