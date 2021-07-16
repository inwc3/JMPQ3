package systems.crigges.jmpq3;

import systems.crigges.jmpq3.security.MPQEncryption;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;

import static systems.crigges.jmpq3.MpqFile.*;

public class BlockTable {
    private final ByteBuffer blockMap;
    private final int size;

    public BlockTable(ByteBuffer buf) throws IOException {
        this.size = (buf.capacity() / 16);

        blockMap = ByteBuffer.allocate(buf.capacity());
        new MPQEncryption(-326913117, true).processFinal(buf, blockMap);
        this.blockMap.order(ByteOrder.LITTLE_ENDIAN);
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
        this.blockMap.position(pos * 16);
        try {
            return new Block(this.blockMap);
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    public ArrayList<Block> getAllVaildBlocks() throws JMpqException {
        ArrayList<Block> list = new ArrayList<>();
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

        public Block(ByteBuffer buf) throws IOException {
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
            return "Block [filePos=" + this.filePos + ", compressedSize=" + this.compressedSize + ", normalSize=" + this.normalSize + ", flags=" +
                    printFlags().trim() + "]";
        }

        public String printFlags() {
            return (hasFlag(EXISTS) ? "EXISTS " : "") + (hasFlag(SINGLE_UNIT) ? "SINGLE_UNIT " : "") + (hasFlag(COMPRESSED) ? "COMPRESSED " : "")
                    + (hasFlag(ENCRYPTED) ? "ENCRYPTED " : "") + (hasFlag(ADJUSTED_ENCRYPTED) ? "ADJUSTED " : "") + (hasFlag(DELETED) ? "DELETED " : "");
        }
    }
}