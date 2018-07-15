package systems.crigges.jmpq3;

import com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OUTPeer;
import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.file.StandardOpenOption.*;
import static systems.crigges.jmpq3.MpqFile.*;

public class BlockTable {
    private MappedByteBuffer blockMap;
    private final Map<Integer, Integer> blockStartIndexes = new LinkedHashMap<>();
    private int size;

    public BlockTable(ByteBuffer buf) throws IOException {
        this.size = (buf.capacity() / 16);
        
        final ByteBuffer decryptedBuffer = ByteBuffer.allocate(buf.capacity());
        new MPQEncryption(-326913117, true).processFinal(buf, decryptedBuffer);
        byte[] decrypted = decryptedBuffer.array();

        File block = File.createTempFile("block", "jmpq", JMpqEditor.tempDir);
        block.deleteOnExit();

        try (FileOutputStream blockStream = new FileOutputStream(block); FileChannel blockChannel = FileChannel.open(block.toPath(), CREATE, WRITE, READ)) {

            blockStream.write(decrypted);
            blockStream.flush();
            this.blockMap = blockChannel.map(FileChannel.MapMode.READ_WRITE, 0L, blockChannel.size());
            this.blockMap.order(ByteOrder.LITTLE_ENDIAN);

            this.blockMap.position(0);

            int size = this.blockMap.remaining() / 16;

            for (int i = 0; i < size; i++) {
                this.blockMap.position(i * 16);

                int pos = this.blockMap.getInt();

                blockStartIndexes.put(i, pos);
            }
        }
    }

    public static void writeNewBlocktable(ArrayList<Block> blocks, int size, MappedByteBuffer buf) {
        ByteBuffer temp = ByteBuffer.allocate(size * 16);
        temp.order(ByteOrder.LITTLE_ENDIAN);
        for (Block b : blocks) {
            b.writeToBuffer(temp);
        }
        temp.clear();
        if (new MPQEncryption(-326913117, false).processFinal(temp, buf))
            throw new BufferOverflowException(); 
    }

    public Block getBlockAtPos(int pos) throws JMpqException {
        if ((pos < 0) || (pos > this.size)) {
            throw new JMpqException("Invaild block position");
        }
        this.blockMap.position(blockStartIndexes.get(pos));
        try {
            return new Block(this.blockMap);
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    public ArrayList<Block> getAllVaildBlocks() throws JMpqException {
        ArrayList<Block> list = new ArrayList<Block>();
        for (int i = 0; i < this.size; i++) {
            Block b = getBlockAtPos(i);
            if ((b.getFlags() & 0x80000000) == -2147483648) {
                list.add(b);
            }
        }
        return list;
    }

    public static class Block {
        private long filePos;
        private int compressedSize;
        private int normalSize;
        private int flags;

        public Block(MappedByteBuffer buf) throws IOException {
            this.filePos = buf.getInt();
            this.compressedSize = buf.getInt();
            this.normalSize = buf.getInt();
            this.flags = buf.getInt();
        }

        public Block(long filePos, int compressedSize, int normalSize, int flags) {
            this.filePos = filePos;
            this.compressedSize = compressedSize;
            this.normalSize = normalSize;
            this.flags = flags;
        }

        public void writeToBuffer(ByteBuffer bb) {
            bb.putInt((int) this.filePos);
            bb.putInt(this.compressedSize);
            bb.putInt(this.normalSize);
            bb.putInt(this.flags);
        }

        public int getFilePos() {
            return (int) this.filePos;
        }

        public int getCompressedSize() {
            return this.compressedSize;
        }

        public int getNormalSize() {
            return this.normalSize;
        }

        public int getFlags() {
            return this.flags;
        }

        public void setFilePos(int filePos) {
            this.filePos = filePos;
        }

        public void setCompressedSize(int compressedSize) {
            this.compressedSize = compressedSize;
        }

        public void setNormalSize(int normalSize) {
            this.normalSize = normalSize;
        }

        public void setFlags(int flags) {
            this.flags = flags;
        }

        public boolean hasFlag(int flag) {
            return (flags & flag) == flag;
        }

        public String toString() {
            return "Block [filePos=" + String.format("%x", this.filePos) + ", compressedSize=" + this.compressedSize + ", normalSize=" + this.normalSize + ", flags=" +
                    printFlags().trim() + "]";
        }

        public String printFlags() {
            return (hasFlag(EXISTS) ? "EXISTS " : "") + (hasFlag(SINGLE_UNIT) ? "SINGLE_UNIT " : "") + (hasFlag(COMPRESSED) ? "COMPRESSED " : "")
                    + (hasFlag(ENCRYPTED) ? "ENCRYPTED " : "") + (hasFlag(ADJUSTED_ENCRYPTED) ? "ADJUSTED " : "") + (hasFlag(DELETED) ? "DELETED " : "");
        }
    }
}