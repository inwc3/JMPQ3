
package systems.crigges.jmpq3;

import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.CompressionUtil;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.*;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;

public class MpqFile {
    public static final int COMPRESSED = 0x00000200;
    public static final int ENCRYPTED = 0x00010000;
    public static final int SINGLE_UNIT = 0x01000000;
    public static final int ADJUSTED_ENCRYPTED = 0x00020000;
    public static final int EXISTS = 0x80000000;
    public static final int DELETED = 0x02000000;
    public static final int IMPLODED = 0x00000100;

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
            final MPQHashGenerator keyGen = MPQHashGenerator.getFileKeyGenerator();
            keyGen.process(pathlessName);
            baseKey = keyGen.getHash();
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
        if (extractImplodedBlock(writer)) return;
        if (extractSingleUnitBlock(writer)) return;
        if (block.hasFlag(COMPRESSED)) {
            extractCompressedBlock(writer);
        } else {
            check(writer);
        }
    }

    private void extractCompressedBlock(OutputStream writer) throws IOException {
        buf.position(0);
        byte[] sot = new byte[sectorCount * 4];
        buf.get(sot);
        if (isEncrypted) {
            new MPQEncryption(baseKey - 1, true).processSingle(ByteBuffer.wrap(sot));
        }
        ByteBuffer sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
        int start = sotBuffer.getInt();
        int end = sotBuffer.getInt();
        int finalSize = 0;
        for (int i = 0; i < sectorCount - 1; i++) {
            buf.position(0 + start);
            byte[] arr = getSectorAsByteArray(buf, end - start);
            if (isEncrypted) {
                new MPQEncryption(baseKey + i, true).processSingle(ByteBuffer.wrap(arr));
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
    }

    private boolean extractSingleUnitBlock(OutputStream writer) throws IOException {
        if (block.hasFlag(SINGLE_UNIT)) {
            if (block.hasFlag(COMPRESSED)) {
                buf.position(0);
                byte[] arr = getSectorAsByteArray(buf, compressedSize);
                if (isEncrypted) {
                    new MPQEncryption(baseKey, true).processSingle(ByteBuffer.wrap(arr));
                }
                arr = decompressSector(arr, block.getCompressedSize(), block.getNormalSize());
                writer.write(arr);
                writer.flush();
                writer.close();
            } else {
                check(writer);
            }
            return true;
        }
        return false;
    }

    private boolean extractImplodedBlock(OutputStream writer) throws IOException {
        if (block.hasFlag(IMPLODED)) {
            buf.position(0);
            byte[] sot = new byte[sectorCount * 4];
            buf.get(sot);
            if (isEncrypted) {
                new MPQEncryption(baseKey - 1, true).processSingle(ByteBuffer.wrap(sot));
            }
            ByteBuffer sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
            int start = sotBuffer.getInt();
            int end = sotBuffer.getInt();
            int finalSize = 0;
            for (int i = 0; i < sectorCount - 1; i++) {
                buf.position(0 + start);
                byte[] arr = getSectorAsByteArray(buf, end - start);
                if (isEncrypted) {
                    new MPQEncryption(baseKey + i, true).processSingle(ByteBuffer.wrap(arr));
                }
                if (block.getNormalSize() - finalSize <= sectorSize) {
                    arr = decompressImplodedSector(arr, end - start, block.getNormalSize() - finalSize);
                } else {
                    arr = decompressImplodedSector(arr, end - start, sectorSize);
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
            return true;
        }
        return false;
    }

    private void check(OutputStream writer) throws IOException {
        buf.position(0);
        byte[] arr = getSectorAsByteArray(buf, compressedSize);
        if (isEncrypted) {
            new MPQEncryption(baseKey, true).processSingle(ByteBuffer.wrap(arr));
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
            if (block.hasFlag(ENCRYPTED)) {
                new MPQEncryption(baseKey, true).processSingle(ByteBuffer.wrap(arr));
            }
            writeBuffer.put(arr);

            if (block.hasFlag(SINGLE_UNIT)) {
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
                new MPQEncryption(baseKey - 1, true).processSingle(ByteBuffer.wrap(sot));
            }
            writeBuffer.put(sot);
            ByteBuffer sotBuffer = ByteBuffer.wrap(sot).order(ByteOrder.LITTLE_ENDIAN);
            int start = sotBuffer.getInt();
            int end = sotBuffer.getInt();
            for (int i = 0; i < sectorCount - 1; i++) {
                buf.position(0 + start);
                byte[] arr = getSectorAsByteArray(buf, end - start);
                if (isEncrypted) {
                    new MPQEncryption(baseKey + i, true).processSingle(ByteBuffer.wrap(arr));
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
     * @param b          the b
     * @param buf        the buf
     * @param sectorSize the sector size
     * @param recompress
     */
    public static void writeFileAndBlock(byte[] file, Block b, MappedByteBuffer buf, int sectorSize, RecompressOptions recompress) {
        writeFileAndBlock(file, b, buf, sectorSize, "", recompress);
    }

    /**
     * Write file and block.
     *  @param fileArr    the file arr
     * @param b          the b
     * @param buf        the buf
     * @param sectorSize the sector size
     * @param recompress
     */
    public static void writeFileAndBlock(byte[] fileArr, Block b, MappedByteBuffer buf, int sectorSize, String pathlessName, RecompressOptions recompress) {
        ByteBuffer fileBuf = ByteBuffer.wrap(fileArr);
        fileBuf.position(0);
        b.setNormalSize(fileArr.length);
        if (b.getFlags() == 0) {
            if (fileArr.length > 0) {
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
                compSector = CompressionUtil.compress(temp, recompress);
            } catch (ArrayIndexOutOfBoundsException ignored) {
            }
            if (compSector != null && compSector.length+1 < temp.length) {
                if (b.hasFlag(ENCRYPTED)) {
                    final MPQHashGenerator keyGen = MPQHashGenerator.getFileKeyGenerator();
                    keyGen.process(pathlessName);
                    int bKey = keyGen.getHash();
                    if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                        bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
                    }
                    
                    if (new MPQEncryption(bKey + i, false).processFinal(
                            ByteBuffer.wrap(DebugHelper.appendData((byte) 2, compSector), 0, compSector.length + 1), buf))
                        throw new BufferOverflowException(); 
                } else {
                    // deflate compression indicator
                    buf.put((byte) 2);
                    buf.put(compSector);
                }
                sotPos += compSector.length + 1;
            } else {
                if (b.hasFlag(ENCRYPTED)) {
                    final MPQHashGenerator keyGen = MPQHashGenerator.getFileKeyGenerator();
                    keyGen.process(pathlessName);
                    int bKey = keyGen.getHash();
                    if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                        bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
                    }
                    if (new MPQEncryption(bKey + i, false).processFinal(ByteBuffer.wrap(temp), buf))
                        throw new BufferOverflowException(); 
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
            final MPQHashGenerator keyGen = MPQHashGenerator.getFileKeyGenerator();
            keyGen.process(pathlessName);
            int bKey = keyGen.getHash();
            if (b.hasFlag(ADJUSTED_ENCRYPTED)) {
                bKey = ((bKey + b.getFilePos()) ^ b.getNormalSize());
            }
            if (new MPQEncryption(bKey - 1, false).processFinal(sot, buf))
                throw new BufferOverflowException(); 
        } else {
            buf.put(sot);
        }
    }

    /**
     * Gets the sector as byte array.
     *
     * @param buf        the buf
     * @param sectorSize the sector size
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
     * @param sector           the sector
     * @param normalSize       the normal size
     * @param uncompressedSize the uncomp size
     * @return the byte[]
     * @throws JMpqException the j mpq exception
     */
    private byte[] decompressSector(byte[] sector, int normalSize, int uncompressedSize) throws JMpqException {
        return CompressionUtil.decompress(sector, normalSize, uncompressedSize);
    }

    private byte[] decompressImplodedSector(byte[] sector, int normalSize, int uncompressedSize) throws JMpqException {
        return CompressionUtil.explode(sector, normalSize, uncompressedSize);
    }

    @Override
    public String toString() {
        return "MpqFile [sectorSize=" + sectorSize + ", compressedSize=" + compressedSize + ", normalSize=" + normalSize + ", flags=" + flags + ", name=" + name
                + "]";
    }
}
