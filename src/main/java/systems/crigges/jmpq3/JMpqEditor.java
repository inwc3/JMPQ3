package systems.crigges.jmpq3;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.crigges.jmpq3.BlockTable.Block;
import systems.crigges.jmpq3.compression.RecompressOptions;
import systems.crigges.jmpq3.security.MPQEncryption;
import systems.crigges.jmpq3.security.MPQHashGenerator;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.util.*;

import static systems.crigges.jmpq3.MpqFile.*;

/**
 * Provides an interface for using MPQ archive files. MPQ archive files contain
 * a virtual file system used by some old games to hold data, primarily those
 * from Blizzard Entertainment.
 * <p>
 * MPQ archives are not intended as a general purpose file system. File access
 * and reading is highly efficient. File manipulation and writing is not
 * efficient and may require rebuilding a large portion of the archive file.
 * Empty directories are not supported. The full contents of the archive might
 * not be discoverable, but such files can still be accessed if their full path
 * is known. File attributes are optional.
 * <p>
 * For platform independence the implementation is pure Java.
 */
public class JMpqEditor implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());
    public static final int ARCHIVE_HEADER_MAGIC = ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1A}).order(ByteOrder.LITTLE_ENDIAN).getInt();
    public static final int USER_DATA_HEADER_MAGIC = ByteBuffer.wrap(new byte[]{'M', 'P', 'Q', 0x1B}).order(ByteOrder.LITTLE_ENDIAN).getInt();
    private static final long V0_MAX_ARCHIVE_SIZE = 0xFFFFFFFFL;
    private static final int WARCRAFT_V0_HASH_TABLE_SIZE = 0x10000;
    private static final int WARCRAFT_V0_MAX_FILE_ENTRIES = WARCRAFT_V0_HASH_TABLE_SIZE - 2;

    /**
     * Encryption key for hash table data.
     */
    private static final int KEY_HASH_TABLE;

    /**
     * Encryption key for block table data.
     */
    private static final int KEY_BLOCK_TABLE;

    static {
        final MPQHashGenerator hasher = MPQHashGenerator.getFileKeyGenerator();
        hasher.process("(hash table)");
        KEY_HASH_TABLE = hasher.getHash();
        hasher.reset();
        hasher.process("(block table)");
        KEY_BLOCK_TABLE = hasher.getHash();
    }

    public static File tempDir;
    private AttributesFile attributes;
    /**
     * MPQ format version 0 forced compatibility is being used.
     */
    private final boolean legacyCompatibility;
    /**
     * The fc.
     */
    private final SeekableInMemoryByteChannel fc;
    /**
     * The header offset.
     */
    private long headerOffset;
    /**
     * The header size.
     */
    private int headerSize;
    /**
     * The archive size.
     */
    private long archiveSize;
    /**
     * The format version.
     */
    private int formatVersion;
    /**
     * The sector size shift
     */
    private int sectorSizeShift;
    /**
     * The disc block size.
     */
    private int discBlockSize;
    /**
     * The hash table file position.
     */
    private long hashPos;
    /**
     * The block table file position.
     */
    private long blockPos;
    /**
     * The hash size.
     */
    private int hashSize;
    /**
     * The block size.
     */
    private int blockSize;
    /**
     * The hash table.
     */
    private HashTable hashTable;
    /**
     * The block table.
     */
    private BlockTable blockTable;
    /**
     * The list file.
     */
    private Listfile listFile = new Listfile();
    /**
     * The internal filename.
     */
    static class Either {
        Path path;
        byte[] data;

        Either(Path path) {
            this.path = path;
        }

        Either(byte[] data) {
            this.data = data;
        }

    }
    private final LinkedIdentityHashMap<String, Either> filenameToData = new LinkedIdentityHashMap<>();
    /** The files to add. */
    /**
     * The keep header offset.
     */
    private boolean keepHeaderOffset = true;
    /**
     * The new header size.
     */
    private int newHeaderSize;
    /**
     * The new archive size.
     */
    private long newArchiveSize;
    /**
     * The new format version.
     */
    private int newFormatVersion;
    /**
     * The new disc block size.
     */
    private int newSectorSizeShift;
    /**
     * The new disc block size.
     */
    private int newDiscBlockSize;
    /**
     * The new hash pos.
     */
    private long newHashPos;
    /**
     * The new block pos.
     */
    private long newBlockPos;
    /**
     * The new hash size.
     */
    private int newHashSize;
    /**
     * The new block size.
     */
    private int newBlockSize;

    /**
     * If write operations are supported on the archive.
     */
    private boolean canWrite;
    private byte[] outputByteArray;

    public JMpqEditor(byte[] mpqArchive, MPQOpenOption... openOptions) throws JMpqException {
        // process open options
        canWrite = !Arrays.asList(openOptions).contains(MPQOpenOption.READ_ONLY);
        legacyCompatibility = Arrays.asList(openOptions).contains(MPQOpenOption.FORCE_V0);
        try {
            setupTempDir();

            fc = new SeekableInMemoryByteChannel(mpqArchive);

            readMpq();
        } catch (IOException e) {
            throw new JMpqException("Byte array mpq: " + e.getMessage());
        }
    }

    public JMpqEditor(Path mpq, MPQOpenOption... openOptions) throws IOException {
        this(Files.readAllBytes(mpq), openOptions);
    }
    public JMpqEditor(File mpq, MPQOpenOption... openOptions) throws IOException {
        this(mpq.toPath(), openOptions);
    }

    private void readMpq() throws IOException {
        headerOffset = searchHeader();

        readHeaderSize();

        readHeader();

        checkLegacyCompat();

        readHashTable();

        readBlockTable();

        readListFile();

        readAttributesFile();
    }

    private void checkLegacyCompat() throws IOException {
        if (legacyCompatibility) {
            // limit end of archive by end of file
            archiveSize = Math.min(archiveSize, fc.size() - headerOffset);

            // limit block table size by end of archive
            long delta = archiveSize - blockPos;
            if (delta > 0) {
                blockSize = Math.toIntExact((Math.min(blockSize, delta / 16)));
            }
        }
    }

    private void readAttributesFile() {
        if (hasFile("(attributes)")) {
            try {
                attributes = new AttributesFile(extractFileAsBytes("(attributes)"));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * For use when the MPQ is missing a (listfile)
     * Adds this custom listfile into the MPQ and uses it
     * for rebuilding purposes.
     * If this is not a full listfile, the end result will be missing files.
     *
     * @param externalListfilePath Path to a file containing listfile entries
     */
    public void setExternalListfile(File externalListfilePath) {
        if (!canWrite) {
            log.warn("The mpq was opened as readonly, setting an external listfile will have no effect.");
            return;
        }
        if (!externalListfilePath.exists()) {
            log.warn("External MPQ File: " + externalListfilePath.getAbsolutePath() +
                " does not exist and will not be used");
            return;
        }
        try {
            // Read and apply listfile
            listFile = new Listfile(Files.readAllBytes(externalListfilePath.toPath()));
            checkListfileEntries();
            // Operation succeeded and added a listfile so we can now write properly.
            // (as long as it wasn't read-only to begin with)
        } catch (Exception ex) {
            log.warn("Could not apply external listfile: " + externalListfilePath.getAbsolutePath());
            // The value of canWrite is not changed intentionally
        }
    }

    /**
     * Reads an internal Listfile name called (listfile)
     * and applies that as the archive's listfile.
     */
    private void readListFile() {
        if (hasFile("(listfile)")) {
            try {
                listFile = new Listfile(extractFileAsBytes("(listfile)"));
                checkListfileEntries();
            } catch (Exception e) {
                log.warn("Extracting the mpq's listfile failed. It cannot be rebuild.", e);
            }
        } else {
            log.warn("The mpq doesn't contain a listfile. Unknown blocks will be discarded.");
//            canWrite = false;
        }
    }

    /**
     * Performs verification to see if we know all the blocks of this file.
     * Prints warnings if we don't know all blocks.
     *
     * @throws JMpqException If retrieving valid blocks fails
     */
    private void checkListfileEntries() throws JMpqException {
        int hiddenFiles = (hasFile("(attributes)") ? 2 : 1) + (hasFile("(signature)") ? 1 : 0);
        if (canWrite) {
            addDefaultListfileEntriesIfIncomplete(hiddenFiles);
            checkListfileCompleteness(hiddenFiles);
        }
    }

    private void addDefaultListfileEntriesIfIncomplete(int hiddenFiles) throws JMpqException {
        int requiredNamedFiles = blockTable.getAllVaildBlocks().size() - hiddenFiles;
        int knownArchiveEntries = countKnownArchiveListfileEntries();
        if (knownArchiveEntries >= requiredNamedFiles) {
            return;
        }

        log.info("Internal listfile is incomplete ({}/{} known archive entries). Probing bundled DefaultListfile.txt for known Warcraft III files.",
            knownArchiveEntries, requiredNamedFiles);

        int added = 0;
        InputStream resource = getClass().getClassLoader().getResourceAsStream("DefaultListfile.txt");
        if (resource == null) {
            log.warn("DefaultListfile.txt not found. Unknown blocks cannot be rebuilt without their names.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String fileName;
            while ((fileName = reader.readLine()) != null) {
                if (!listFile.containsFile(fileName) && hasFile(fileName)) {
                    listFile.addFile(fileName);
                    added++;
                }
            }
        } catch (IOException e) {
            throw new JMpqException(e);
        }

        if (added > 0) {
            log.info("Default listfile discovery added {} real archive entries before rebuild.", added);
        } else {
            log.info("Default listfile discovery did not find additional known archive entries.");
        }
    }

    private int countKnownArchiveListfileEntries() {
        int knownArchiveEntries = 0;
        for (String fileName : listFile.getFiles()) {
            if (hasFile(fileName)) {
                knownArchiveEntries++;
            }
        }
        return knownArchiveEntries;
    }

    /**
     * Checks listfile for completeness against block table
     *
     * @param hiddenFiles Num. hidden files
     * @throws JMpqException If retrieving valid blocks fails
     */
    private void checkListfileCompleteness(int hiddenFiles) throws JMpqException {
        for (String fileName : listFile.getFiles()) {
            if (!hasFile(fileName)) {
                log.warn("listfile entry does not exist in archive and will be discarded: " + fileName);
            }
        }
        listFile.getFileMap().entrySet().removeIf(file -> !hasFile(file.getValue()));
        int requiredNamedFiles = blockTable.getAllVaildBlocks().size() - hiddenFiles;
        if (listFile.getFiles().size() < requiredNamedFiles) {
            log.warn("mpq's listfile is incomplete. Blocks without a known filename will be preserved under generated names during rebuild.");
        }
    }

    private void readBlockTable() throws IOException {
        System.out.println("blockSize: " + blockSize);
        ByteBuffer blockBuffer = ByteBuffer.allocate(blockSize * 16).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + blockPos);
        readFully(blockBuffer, fc);
        blockBuffer.rewind();
        blockTable = new BlockTable(blockBuffer);
    }

    private void readHashTable() throws IOException {
        // read hash table
        ByteBuffer hashBuffer = ByteBuffer.allocate(hashSize * 16);
        fc.position(headerOffset + hashPos);
        readFully(hashBuffer, fc);
        hashBuffer.rewind();

        // decrypt hash table
        final MPQEncryption decrypt = new MPQEncryption(KEY_HASH_TABLE, true);
        decrypt.processSingle(hashBuffer);
        hashBuffer.rewind();

        // create hash table
        hashTable = new HashTable(hashSize);
        hashTable.readFromBuffer(hashBuffer);
    }

    private void readHeaderSize() throws IOException {
        // probe to sample file with
        ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        // read header size
        fc.position(headerOffset + 4);
        readFully(probe, fc);
        headerSize = probe.getInt(0);
        if (legacyCompatibility) {
            // force version 0 header size
            headerSize = 32;
        } else if (headerSize < 32 || 208 < headerSize) {
            // header too small or too big
            throw new JMpqException("Bad header size.");
        }
    }

    private void setupTempDir() throws JMpqException {
        try {
            Path path = Paths.get(System.getProperty("java.io.tmpdir") + "jmpq");
            JMpqEditor.tempDir = path.toFile();
            if (!JMpqEditor.tempDir.exists())
                Files.createDirectory(path);

            File[] files = JMpqEditor.tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        } catch (IOException e) {
            try {
                JMpqEditor.tempDir = Files.createTempDirectory("jmpq").toFile();
            } catch (IOException e1) {
                throw new JMpqException(e1);
            }
        }
    }

//    /**
//     * Loads a default listfile for mpqs that have none
//     * Makes the archive readonly.
//     */
//    private void loadDefaultListFile() throws IOException {
//        log.warn("The mpq doesn't come with a listfile so it cannot be rebuild");
//        InputStream resource = getClass().getClassLoader().getResourceAsStream("DefaultListfile.txt");
//        if (resource != null) {
//            File tempFile = File.createTempFile("jmpq", "lf", tempDir);
//            tempFile.deleteOnExit();
//            try (FileOutputStream out = new FileOutputStream(tempFile)) {
//                //copy stream
//                byte[] buffer = new byte[1024];
//                int bytesRead;
//                while ((bytesRead = resource.read(buffer)) != -1) {
//                    out.write(buffer, 0, bytesRead);
//                }
//            }
//            listFile = new Listfile(Files.readAllBytes(tempFile.toPath()));
//            canWrite = false;
//        }
//    }


    /**
     * Searches the file for the MPQ archive header.
     *
     * @return the file position at which the MPQ archive starts.
     * @throws IOException   if an error occurs while searching.
     * @throws JMpqException if file does not contain a MPQ archive.
     */
    private long searchHeader() throws IOException {
        // probe to sample file with
        ByteBuffer probe = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

        final long fileSize = fc.size();
        for (long filePos = 0; filePos + probe.capacity() < fileSize; filePos += 0x200) {
            probe.rewind();
            fc.position(filePos);
            readFully(probe, fc);

            final int sample = probe.getInt(0);
            if (sample == ARCHIVE_HEADER_MAGIC) {
                if (legacyCompatibility && !isWarcraftCompatibleHeader(filePos)) {
                    continue;
                }
                // found archive header
                return filePos;
            } else if (sample == USER_DATA_HEADER_MAGIC && !legacyCompatibility) {
                // MPQ user data header with redirect to MPQ header
                // ignore in legacy compatibility mode

                // TODO process these in some meaningful way

                probe.rewind();
                fc.position(filePos + 8);
                readFully(probe, fc);

                // Add the user-data header offset. Warcraft III ignores MPQ
                // user-data headers, but generic MPQ tools follow them exactly.
                long redirectedFilePos = filePos + (probe.getInt(0) & 0xFFFFFFFFL);
                if (redirectedFilePos + probe.capacity() < fileSize) {
                    probe.rewind();
                    fc.position(redirectedFilePos);
                    readFully(probe, fc);
                    if (probe.getInt(0) == ARCHIVE_HEADER_MAGIC) {
                        return redirectedFilePos;
                    }
                }
            }
        }

        throw new JMpqException("No MPQ archive in file.");
    }

    private boolean isWarcraftCompatibleHeader(long filePos) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(filePos);
        readFully(header, fc);
        header.rewind();
        if (header.getInt() != ARCHIVE_HEADER_MAGIC) {
            return false;
        }
        return header.getInt() == 32 && header.getShort(12) == 0;
    }

    /**
     * Read the MPQ archive header from the header chunk.
     */
    private void readHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(buffer, fc);
        buffer.rewind();

        archiveSize = buffer.getInt() & 0xFFFFFFFFL;
        formatVersion = buffer.getShort();
        if (legacyCompatibility) {
            // force version 0 interpretation
            formatVersion = 0;
        }

        sectorSizeShift = buffer.getShort();
        discBlockSize = 512 * (1 << sectorSizeShift);
        hashPos = buffer.getInt() & 0xFFFFFFFFL;
        blockPos = buffer.getInt() & 0xFFFFFFFFL;
        hashSize = buffer.getInt() & 0x0FFFFFFF;
        blockSize = buffer.getInt();

        // version 1 extension
        if (formatVersion >= 1) {
            // TODO add high block table support
            buffer.getLong();

            // high 16 bits of file pos
            hashPos |= (buffer.getShort() & 0xFFFFL) << 32;
            blockPos |= (buffer.getShort() & 0xFFFFL) << 32;
        }

        // version 2 extension
        if (formatVersion >= 2) {
            // 64 bit archive size
            archiveSize = buffer.getLong();

            // TODO add support for BET and HET tables
            buffer.getLong();
            buffer.getLong();
        }

        // version 3 extension
        if (formatVersion >= 3) {
            // TODO add support for compression and checksums
            buffer.getLong();
            buffer.getLong();
            buffer.getLong();
            buffer.getLong();
            buffer.getLong();

            buffer.getInt();
            final byte[] md5 = new byte[16];
            buffer.get(md5);
            buffer.get(md5);
            buffer.get(md5);
            buffer.get(md5);
            buffer.get(md5);
            buffer.get(md5);
        }
    }

    /**
     * Write header.
     *
     * @param buffer the buffer
     */
    private void writeHeader(ByteBuffer buffer) {
        buffer.putInt(newHeaderSize);
        putUnsignedInt(buffer, newArchiveSize, "Archive size");
        buffer.putShort((short) newFormatVersion);
        buffer.putShort((short) newSectorSizeShift);
        putUnsignedInt(buffer, newHashPos, "Hash table position");
        putUnsignedInt(buffer, newBlockPos, "Block table position");
        buffer.putInt(newHashSize);
        buffer.putInt(newBlockSize);
        // TODO add full write support for versions above 1
    }

    private static void putUnsignedInt(ByteBuffer buffer, long value, String fieldName) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(fieldName + " exceeds unsigned 32-bit range: " + value);
        }
        buffer.putInt((int) value);
    }

    private static int nextPowerOfTwo(int value) throws JMpqException {
        if (value <= 0) {
            throw new JMpqException("Hash table target size must be positive.");
        }
        if (value > (1 << 30)) {
            throw new JMpqException("Hash table target size is too large: " + value);
        }

        int current = 1;
        while (current < value) {
            current <<= 1;
        }
        return current;
    }

    private static void validateV0TableSizes(int hashTableSize, int blockTableSize) throws JMpqException {
        if (hashTableSize <= 0 || hashTableSize > HashTable.BLOCK_INDEX_MASK) {
            throw new JMpqException("Hash table size exceeds V0-compatible mask: " + hashTableSize);
        }
        if (blockTableSize < 0 || blockTableSize > HashTable.BLOCK_INDEX_MASK) {
            throw new JMpqException("Block table size exceeds V0-compatible mask: " + blockTableSize);
        }
    }

    private int calculateMaxFakeFilesCount(long dataSize, int baseFileCount, int baseBlockCount) throws JMpqException {
        final int fakeEntriesPerCount = baseBlockCount > 0 ? 3 : 1;
        final int extraFakeEntries = hasFile("war3map.j") ? 1 : 0;
        int high = (WARCRAFT_V0_MAX_FILE_ENTRIES - baseFileCount - extraFakeEntries) / fakeEntriesPerCount;
        if (high < 0) {
            return 0;
        }

        long totalFiles = (long) baseFileCount + (long) fakeEntriesPerCount * high + extraFakeEntries;
        long archiveSize = dataSize + (long) WARCRAFT_V0_HASH_TABLE_SIZE * 16L + (totalFiles + 2L) * 16L;
        if (archiveSize > V0_MAX_ARCHIVE_SIZE) {
            throw new JMpqException("Cannot maximize V0 tables without exceeding unsigned 32-bit archive size.");
        }

        return high;
    }

    private static String buildFakeVisiblePath(String family, int index) {
        final String[] abilitySets = new String[]{
            "AcidBomb", "HealingSpray", "Incinerate", "SoulBurn", "Transmute", "Volcano",
            "TinkerRocket", "Possession", "ShadowStrike", "Doom", "BreathOfFire", "ClusterRockets"
        };
        final String[] abilityFiles = new String[]{
            "BottleMissile.mdx", "BottleImpact.mdx", "AlchemistAcidBurnMissileDeath1.wav",
            "HealBottleMissile.mdx", "HealingSprayBirth1.wav", "HealingSprayDeath1.wav",
            "Incinerate1.wav", "IncinerateBuff.mdx", "Torch2.blp", "GoldBottleMissile.mdx",
            "Green_Firering2b.blp", "PileofGold.mdx", "Volcano.mdx", "VolcanoLoop.wav",
            "VolcanoMissile.mdx", "TinkerRocketMissile.mdx", "PossessionCaster.mdx"
        };
        final String[] buttonFiles = new String[]{
            "BTNAcidBomb.blp", "BTNChemicalRage.blp", "BTNClusterRockets.blp", "BTNCritterChicken.blp",
            "BTNCritterRabbit.blp", "BTNHeroAlchemist.blp", "BTNHeroAvatarOfFlame.blp",
            "BTNHeroTinker.blp", "BTNIncinerate.blp", "BTNLavaSpawn.blp", "BTNPocketFactory.blp",
            "BTNRacoon.blp", "BTNROBOGOBLIN.blp", "BTNTransmute.blp", "BTNVolcano.blp"
        };
        final String[] unitSets = new String[]{
            "HeroFlameLord", "HEROGoblinALCHEMIST", "HeroTinker", "HeroTinkerFactory",
            "HeroTinkerRobot", "LavaSpawn", "EasterChicken", "EasterRabbit", "Frog", "Raccoon"
        };
        final String[] unitFiles = new String[]{
            "HeroFlameLord.mdx", "HeroFlameLord_Portrait.mdx", "HeroGoblinAlchemistPurple.blp",
            "HEROGoblinALCHEMIST.mdx", "HEROGoblinALCHEMIST_Portrait.mdx", "HeroTinker.mdx",
            "HeroTinker_Portrait.mdx", "HeroTinkerTank.blp", "PocketFactoryBirth.wav",
            "PocketFactoryLaunch.wav", "LavaSpawn.mdx", "LavaSpawn_Portrait.mdx", "RabbitSkin.blp",
            "Raccoon.mdx", "Raccoon_Portrait.mdx"
        };
        final String[] dataFiles = new String[]{
            "AbilityBuffData.slk", "AbilityData.slk", "AbilityMetaData.slk", "CampaignUnitStrings.txt",
            "CommandStrings.txt", "CommonAbilityFunc.txt", "DestructableData.slk", "HumanAbilityFunc.txt",
            "HumanUnitFunc.txt", "ItemData.slk", "ItemFunc.txt", "MiscData.txt", "NeutralUnitFunc.txt",
            "NightElfUnitFunc.txt", "OrcUnitFunc.txt", "UnitAbilities.slk", "UnitBalance.slk",
            "UnitData.slk", "unitUI.slk", "UnitWeapons.slk", "UpgradeData.slk"
        };
        final String[] uiFiles = new String[]{
            "MiscData.txt", "SkinMetaData.slk", "TriggerData.txt", "TriggerStrings.txt",
            "WorldEditData.txt", "WorldEditGameStrings.txt", "WorldEditLayout.txt", "WorldEditStrings.txt",
            "FrameDef\\GlobalStrings.fdf", "FrameDef\\Glue\\BattleNetChatPanel.fdf",
            "FrameDef\\Glue\\BattleNetMain.fdf", "Widgets\\BattleNet\\bnet-mainmenu-profile-up.blp",
            "Widgets\\BattleNet\\chaticons\\sponsor-blizzard.blp", "Minimap\\minimap-gold-entangled.blp"
        };
        final String[] dialogueSets = new String[]{
            "OrcQuest12x", "OrcQuest13x", "OrcQuest14x", "OrcQuest17x", "OrcQuest18x",
            "OrcQuest19x", "OrcQuest20x"
        };
        final String[] dialogueFiles = new String[]{
            "D12Jaina06.mp3", "D12Rexxar05.mp3", "D13Jaina16.mp3", "D13Proudmoore24.mp3",
            "D14Voljin04.mp3", "D17Thrall01.mp3", "D18Rokhan01.mp3", "D19Rexxar10.mp3",
            "D20Cairne02.mp3", "D20Thrall14.mp3"
        };
        final String[] rootFiles = new String[]{
            "war3map.j", "war3map.w3e", "war3map.w3i", "war3map.wtg", "war3map.wct",
            "war3map.wts", "war3map.shd", "war3mapMap.blp", "war3mapMap.tga",
            "war3mapPreview.tga", "war3map.mmp", "war3map.doo", "war3mapUnits.doo",
            "war3map.w3r", "war3map.w3c", "war3map.w3s", "war3map.imp"
        };

        int selector = Math.floorMod(index + family.hashCode(), 18);
        int variant = (index / 997) + 1;
        String invisible = (index & 0x1F) == 1 ? "\u200B" : "";
        String space = (index & 0x3F) == 2 ? " " : "";

        switch (selector) {
            case 0:
                return "Abilities\\Spells\\Other\\" + abilitySets[Math.floorMod(index, abilitySets.length)] + "\\" +
                    withVariant(abilityFiles[Math.floorMod(index * 7, abilityFiles.length)], variant, invisible, space);
            case 1:
                return "Abilities\\Weapons\\" + abilitySets[Math.floorMod(index * 3, abilitySets.length)] + "Missile\\" +
                    withVariant(abilityFiles[Math.floorMod(index * 5, abilityFiles.length)], variant, invisible, space);
            case 2:
                return "ReplaceableTextures\\CommandButtons\\" + withVariant(buttonFiles[Math.floorMod(index, buttonFiles.length)], variant, invisible, space);
            case 3:
                return "ReplaceableTextures\\CommandButtonsDisabled\\DIS" + withVariant(buttonFiles[Math.floorMod(index, buttonFiles.length)], variant, invisible, space).replace("BTN", "BTN");
            case 4:
                return "ReplaceableTextures\\PassiveButtons\\PAS" + withVariant(buttonFiles[Math.floorMod(index, buttonFiles.length)], variant, invisible, space);
            case 5:
                return "Units\\Creeps\\" + unitSets[Math.floorMod(index, unitSets.length)] + "\\" +
                    withVariant(unitFiles[Math.floorMod(index * 11, unitFiles.length)], variant, invisible, space);
            case 6:
                return "Units\\" + withVariant(dataFiles[Math.floorMod(index, dataFiles.length)], variant, invisible, space);
            case 7:
                return "Custom_V" + Math.floorMod(index, 2) + "\\Units\\" + withVariant(dataFiles[Math.floorMod(index * 3, dataFiles.length)], variant, invisible, space);
            case 8:
                return "Melee_V" + Math.floorMod(index, 2) + "\\Units\\" + withVariant(dataFiles[Math.floorMod(index * 5, dataFiles.length)], variant, invisible, space);
            case 9:
                return "UI\\" + withVariant(uiFiles[Math.floorMod(index, uiFiles.length)], variant, invisible, space);
            case 10:
                return "Scripts\\" + withVariant("o" + String.format("%02d", Math.floorMod(index, 9) + 1) + "x" + String.format("%02d", Math.floorMod(index / 9, 12) + 1) + ".ai", variant, invisible, space);
            case 11:
                return "Sound\\Dialogue\\OrcExpCamp\\" + dialogueSets[Math.floorMod(index, dialogueSets.length)] + "\\" +
                    withVariant(dialogueFiles[Math.floorMod(index * 7, dialogueFiles.length)], variant, invisible, space);
            case 12:
                return "Maps\\FrozenThrone\\Campaign\\OrcX" + String.format("%02d", Math.floorMod(index, 20) + 1) +
                    "_" + String.format("%02d", Math.floorMod(index / 20, 10) + 1) + ".w3x";
            case 13:
                return withVariant(rootFiles[Math.floorMod(index, rootFiles.length)], variant, invisible, space);
            case 14:
                return withVariant(rootFiles[Math.floorMod(index * 5, rootFiles.length)], variant, "\u200B", "");
            case 15:
                return withVariant(rootFiles[Math.floorMod(index * 7, rootFiles.length)], variant, "", " ");
            case 16:
                return withVariant(rootFiles[Math.floorMod(index * 11, rootFiles.length)], variant, invisible, space);
            default:
                return "Textures\\" + withVariant(unitFiles[Math.floorMod(index * 13, unitFiles.length)].replace(".mdx", ".blp").replace(".wav", ".blp"), variant, invisible, space);
        }
    }

    private void logFakeProgress(String phase, int current, int total) {
        if (total <= 0) {
            return;
        }
        if (current % 10000 == 0 || current == total) {
            log.info("Generated {} / {} {}.", current, total, phase);
        }
    }

    private static String buildUniqueFakeVisiblePath(String family, int index, Set<Long> usedFileKeys) {
        for (int attempt = 0; attempt < 8192; attempt++) {
            String candidate = buildFakeVisiblePath(family, index + attempt * 65536);
            if (reserveFileKey(usedFileKeys, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate unique fake MPQ path for family: " + family);
    }

    private static String buildUniqueLookalikePath(String basePath, Set<Long> usedFileKeys) {
        int dot = basePath.lastIndexOf('.');
        String prefix = dot >= 0 ? basePath.substring(0, dot) : basePath;
        String suffix = dot >= 0 ? basePath.substring(dot) : "";
        String[] variants = new String[]{
            prefix + "\u200B" + suffix,
            prefix + " " + suffix,
            prefix + "  " + suffix,
            prefix + "_" + suffix,
            prefix + "\u200B " + suffix
        };
        for (String candidate : variants) {
            if (reserveFileKey(usedFileKeys, candidate)) {
                return candidate;
            }
        }
        return buildUniqueFakeVisiblePath("scripts", 0, usedFileKeys);
    }

    private static boolean reserveFileKey(Set<Long> usedFileKeys, String candidate) {
        return usedFileKeys.add(HashTable.calculateFileKey(candidate));
    }

    private static String withVariant(String fileName, int variant, String invisible, String space) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName + invisible + space + variant;
        }
        return fileName.substring(0, dot) + invisible + space + "_" + variant + fileName.substring(dot);
    }

    private static byte[] buildFakeListfile(Collection<String> visibleFakeFiles) {
        StringBuilder listfileBuilder = new StringBuilder();
        listfileBuilder.append("(listfile)\r\n");
        for (String fakeFile : visibleFakeFiles) {
            listfileBuilder.append(fakeFile).append("\r\n");
        }
        return listfileBuilder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Extract all files.
     *
     * @param dest the dest
     * @throws JMpqException the j mpq exception
     */
    public void extractAllFiles(File dest) throws JMpqException {
        if (!dest.isDirectory()) {
            throw new JMpqException("Destination location isn't a directory");
        }
        if (hasFile("(listfile)") && listFile != null) {
            for (String s : listFile.getFiles()) {
                String normalized = File.separatorChar == '\\' ? s : s.replace("\\", File.separator);
                log.debug("extracting: " + normalized);
                File temp = new File(dest.getAbsolutePath() + File.separator + normalized);
                temp.getParentFile().mkdirs();
                if (hasFile(s)) {
                    // Prevent exception due to nonexistent listfile entries
                    try {
                        extractFile(s, temp);
                    } catch (JMpqException e) {
                        log.warn("File possibly corrupted and could not be extracted: " + s);
                    }
                }
            }
            if (hasFile("(attributes)")) {
                File temp = new File(dest.getAbsolutePath() + File.separator + "(attributes)");
                extractFile("(attributes)", temp);
            }
            File temp = new File(dest.getAbsolutePath() + File.separator + "(listfile)");
            extractFile("(listfile)", temp);
        } else {
            ArrayList<Block> blocks = blockTable.getAllVaildBlocks();
            try {
                int i = 0;
                for (Block b : blocks) {
                    if (b.hasFlag(MpqFile.ENCRYPTED)) {
                        continue;
                    }
                    ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
                    fc.position(headerOffset + b.getFilePos());
                    readFully(buf, fc);
                    buf.rewind();
                    MpqFile f = new MpqFile(buf, b, discBlockSize, "");
                    f.extractToFile(new File(dest.getAbsolutePath() + File.separator + i));
                    i++;
                }
            } catch (IOException e) {
                throw new JMpqException(e);
            }
        }
    }

    /**
     * Gets the total file count.
     *
     * @return the total file count
     * @throws JMpqException the j mpq exception
     */
    public int getTotalFileCount() throws JMpqException {
        return blockTable.getAllVaildBlocks().size();
    }

    /**
     * Extracts the specified file out of the mpq to the target location.
     *
     * @param name name of the file
     * @param dest destination to that the files content is written
     * @throws JMpqException if file is not found or access errors occur
     */
    public void extractFile(String name, File dest) throws JMpqException {
        try {
            MpqFile f = getMpqFile(name);
            f.extractToFile(dest);
        } catch (Exception e) {
            throw new JMpqException(e);
        }
    }

    /**
     * Extracts the specified file out of the mpq to the target location.
     *
     * @param name name of the file
     * @throws JMpqException if file is not found or access errors occur
     */
    public byte[] extractFileAsBytes(String name) throws JMpqException {
        try {
            MpqFile f = getMpqFile(name);
            return f.extractToBytes();
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    public String extractFileAsString(String name) throws JMpqException {
        try {
            byte[] f = extractFileAsBytes(name);
            return new String(f);
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    /**
     * Checks for file.
     *
     * @param name the name
     * @return true, if successful
     */
    public boolean hasFile(String name) {
        try {
            hashTable.getBlockIndexOfFile(name);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the file names.
     *
     * @return the file names
     */
    public List<String> getFileNames() {
        return new ArrayList<>(listFile.getFiles());
    }

    /**
     * Extracts the specified file out of the mpq and writes it to the target
     * outputstream.
     *
     * @param name name of the file
     * @param dest the outputstream where the file's content is written
     * @throws JMpqException if file is not found or access errors occur
     */
    public void extractFile(String name, OutputStream dest) throws JMpqException {
        try {
            MpqFile f = getMpqFile(name);
            f.extractToOutputStream(dest);
        } catch (IOException e) {
            throw new JMpqException(e);
        }
    }

    /**
     * Gets the mpq file.
     *
     * @param name the name
     * @return the mpq file
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public MpqFile getMpqFile(String name) throws IOException {
        int pos = hashTable.getBlockIndexOfFile(name);
        Block b = blockTable.getBlockAtPos(pos);

        ByteBuffer buffer = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + b.getFilePos());
        readFully(buffer, fc);
        buffer.rewind();

        return new MpqFile(buffer, b, discBlockSize, name);
    }

    /**
     * Gets the mpq file.
     *
     * @param block a block
     * @return the mpq file
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public MpqFile getMpqFileByBlock(BlockTable.Block block) throws IOException {
        if (block.hasFlag(MpqFile.ENCRYPTED)) {
            throw new IOException("cant access this block");
        }
        ByteBuffer buffer = ByteBuffer.allocate(block.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
        fc.position(headerOffset + block.getFilePos());
        readFully(buffer, fc);
        buffer.rewind();

        return new MpqFile(buffer, block, discBlockSize, "");
    }

    /**
     * Gets the mpq files.
     *
     * @return the mpq files
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public List<MpqFile> getMpqFilesByBlockTable() throws IOException {
        List<MpqFile> mpqFiles = new ArrayList<>();
        ArrayList<Block> list = blockTable.getAllVaildBlocks();
        for (Block block : list) {
            try {
                MpqFile mpqFile = getMpqFileByBlock(block);
                mpqFiles.add(mpqFile);
            } catch (IOException ignore) {
            }
        }
        return mpqFiles;
    }

    /**
     * Deletes the specified file out of the mpq once you rebuild the mpq.
     *
     * @param name of the file inside the mpq
     * @throws JMpqException if file is not found or access errors occur
     */
    public void deleteFile(String name) {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }

        if (listFile.containsFile(name)) {
            listFile.removeFile(name);
            filenameToData.remove(name);
        }
    }

    /**
     * Inserts the specified byte array into the mpq once you close the editor.
     *
     * @param name     of the file inside the mpq
     * @param input    the input byte array
     * @param override whether to override an existing file with the same name
     * @throws IllegalArgumentException when the mpq has filename and not override
     */
    public void insertByteArray(String name, byte[] input, boolean override) {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }
        if ((!override) && listFile.containsFile(name)) {
            throw new IllegalArgumentException("Archive already contains file with name: " + name);
        }

        listFile.addFile(name);
        filenameToData.put(name, new Either(input));
    }

    /**
     * Inserts the specified byte array into the mpq once you close the editor.
     *
     * @param name  of the file inside the mpq
     * @param input the input byte array
     * @throws IllegalArgumentException when the mpq has filename
     */
    public void insertByteArray(String name, byte[] input) throws NonWritableChannelException, IllegalArgumentException {
        insertByteArray(name, input, false);
    }

    /**
     * Inserts the specified file into the mpq once you close the editor.
     *
     * @param name of the file inside the mpq
     * @param file the file
     */
    public void insertFile(String name, File file) throws IOException, IllegalArgumentException {
        insertFile(name, file, false);
    }

    /**
     * Inserts the specified file into the mpq once you close the editor.
     *
     * @param name     of the file inside the mpq
     * @param file     the file
     * @param override whether to override an existing file with the same name
     * @throws JMpqException if file is not found or access errors occur
     */
    public void insertFile(String name, File file, boolean override) throws IOException {
        if (!canWrite) {
            throw new NonWritableChannelException();
        }
        log.info("insert file: " + name);
        if ((!override) && listFile.containsFile(name)) {
            throw new IllegalArgumentException("Archive already contains file with name: " + name);
        }

        listFile.addFile(name);
        filenameToData.put(name, new Either(file.toPath())); // Store path, not data
    }

    public void closeReadOnly() throws IOException {
        fc.close();
    }

    public void close() throws IOException {
        close(true, true, false);
    }

    public void close(boolean buildListfile, boolean buildAttributes, boolean recompress) throws IOException {
        close(buildListfile, buildAttributes, new RecompressOptions(recompress), 0);
    }

    /**
     * @param buildListfile   whether or not to add a (listfile) to this mpq
     * @param buildAttributes whether or not to add a (attributes) file to this mpq
     * @param fakeFilesCount  number of fake-file batches to add. Negative means
     *                        calculate the largest V0-compatible count this
     *                        in-memory writer can represent.
     * @throws IOException
     */
    public void close(boolean buildListfile, boolean buildAttributes, RecompressOptions options, int fakeFilesCount) throws IOException {
        // only rebuild if allowed
        if (!canWrite || !fc.isOpen()) {
            fc.close();
            log.debug("closed readonly mpq.");
            return;
        }

        long t = System.nanoTime();
        log.debug("Building mpq");
        if (listFile == null) {
            fc.close();
            return;
        }

        boolean maximizeV0Tables = fakeFilesCount < 0;
        log.info("Building mpq: maxV0Tables={}, requestedFakeBatches={}, buildListfile={}, buildAttributes={}, sourceHashSize={}, sourceBlockSize={}",
            maximizeV0Tables, fakeFilesCount, buildListfile, buildAttributes, hashSize, blockSize);
        DynamicByteBuffer output = new DynamicByteBuffer(Math.toIntExact(archiveSize));
        output.order(ByteOrder.LITTLE_ENDIAN);

        long newArchiveBase = keepHeaderOffset ? headerOffset : 0;
        if (keepHeaderOffset) {
            ByteBuffer headerReader = ByteBuffer.allocate(Math.toIntExact(headerOffset)).order(ByteOrder.LITTLE_ENDIAN);
            fc.position(0);
            readFully(headerReader, fc);
            headerReader.rewind();
            output.put(headerReader);
        } else {
            fc.position(headerOffset);
        }
        output.putInt(ARCHIVE_HEADER_MAGIC);

        newFormatVersion = formatVersion;
        switch (newFormatVersion) {
            case 0:
                newHeaderSize = 32;
                break;
            case 1:
                newHeaderSize = 44;
                break;
            case 2:
            case 3:
                newHeaderSize = 208;
                break;
        }

        output.put(ByteBuffer.allocate(newHeaderSize - 4));
        newSectorSizeShift = options.recompress ? options.newSectorSizeShift : sectorSizeShift;
        newDiscBlockSize = options.recompress ? 512 * (1 << Math.min(newSectorSizeShift, 15)) : discBlockSize;
        ArrayList<Block> newBlocks = new ArrayList<>();
        ArrayList<String> newFiles = new ArrayList<>();
        ArrayList<String> existingFiles = new ArrayList<>(listFile.getFiles());

        sortListfileEntries(existingFiles);

        log.debug("Sorted blocks");
        if (attributes != null) {
            attributes.setNames(existingFiles);
        }
        long currentPos = newArchiveBase + newHeaderSize;

        for (String fileName : filenameToData.keySet()) {
            existingFiles.remove(fileName);
        }

        log.info("Copying {} existing named files.", existingFiles.size());
        int copiedExisting = 0;
        HashSet<Integer> handledSourceBlockIndexes = new HashSet<>();
        for (String existingName : existingFiles) {
            int pos = hashTable.getBlockIndexOfFile(existingName);
            handledSourceBlockIndexes.add(pos);
            if (options.recompress && !existingName.endsWith(".wav")) {
                ByteBuffer extracted = ByteBuffer.wrap(extractFileAsBytes(existingName));
                filenameToData.put(existingName, new Either(extracted.array()));
            } else {
                newFiles.add(existingName);
                Block b = blockTable.getBlockAtPos(pos);
                ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
                fc.position(headerOffset + b.getFilePos());
                readFully(buf, fc);
                buf.rewind();
                MpqFile f = new MpqFile(buf, b, discBlockSize, existingName);
                ByteBuffer fileWriter=  ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
                Block newBlock = new Block(currentPos - newArchiveBase, 0, 0, b.getFlags());
                newBlocks.add(newBlock);
                f.writeFileAndBlock(newBlock, fileWriter);
                fileWriter.rewind();
                output.put(fileWriter);

                currentPos += b.getCompressedSize();
            }
            copiedExisting++;
            if (copiedExisting % 1000 == 0 || copiedExisting == existingFiles.size()) {
                log.info("Copied {} / {} existing files.", copiedExisting, existingFiles.size());
            }
        }
        log.debug("Added existing files");

        // --- BEGIN: merged, minimal fix to use Either from the map (path or data) ---
        HashMap<String, ByteBuffer> newFileMap = new HashMap<>();
        log.info("Writing {} pending inserted/recompressed files.", filenameToData.size());
        int writtenPending = 0;
        for (Map.Entry<String, Either> entry : filenameToData.entrySet()) {
            String newFileName = entry.getKey();
            Either either = entry.getValue();

            byte[] fileData = either.data;
            if (fileData == null && either.path != null) {
                fileData = Files.readAllBytes(either.path);
            }
            if (fileData == null) {
                log.warn("Skipping empty insertion for " + newFileName);
                continue;
            }

            newFiles.add(newFileName);
            ByteBuffer newFileBuf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);
            newFileMap.put(newFileName, newFileBuf);

            int sectorCount = (int) (Math.ceil(((double) fileData.length / (double) newDiscBlockSize)) + 1);
            Block newBlock = new Block(currentPos - newArchiveBase, 0, 0, 0);
            newBlocks.add(newBlock);
            ByteBuffer fileWriter = ByteBuffer.allocate(sectorCount * 4 + (sectorCount - 1) * newDiscBlockSize).order(ByteOrder.LITTLE_ENDIAN);
            MpqFile.writeFileAndBlock(fileData, newBlock, fileWriter, newDiscBlockSize, options);
            currentPos += newBlock.getCompressedSize();
            output.put(fileWriter.array(), 0, newBlock.getCompressedSize());
            log.debug("Added file " + newFileName);
            writtenPending++;
            if (writtenPending % 1000 == 0 || writtenPending == filenameToData.size()) {
                log.info("Wrote {} / {} pending files.", writtenPending, filenameToData.size());
            }
        }
        log.debug("Added new files");
        // --- END: minimal fix ---

        HashSet<Long> usedFileKeys = new HashSet<>();
        for (String newFile : newFiles) {
            usedFileKeys.add(HashTable.calculateFileKey(newFile));
        }

        int preservedUnnamedBlocks = 0;
        for (int sourceBlockIndex = 0; sourceBlockIndex < blockSize; sourceBlockIndex++) {
            if (handledSourceBlockIndexes.contains(sourceBlockIndex)) {
                continue;
            }

            Block b = blockTable.getBlockAtPos(sourceBlockIndex);
            if (!b.hasFlag(EXISTS)) {
                continue;
            }

            ByteBuffer buf = ByteBuffer.allocate(b.getCompressedSize()).order(ByteOrder.LITTLE_ENDIAN);
            fc.position(headerOffset + b.getFilePos());
            readFully(buf, fc);
            buf.rewind();

            String preservedName = buildUniqueFakeVisiblePath("preserved", preservedUnnamedBlocks, usedFileKeys);
            newFiles.add(preservedName);
            Block newBlock = new Block(currentPos - newArchiveBase, b.getCompressedSize(), b.getNormalSize(), b.getFlags());
            newBlocks.add(newBlock);
            output.put(buf);
            currentPos += b.getCompressedSize();
            preservedUnnamedBlocks++;
        }
        if (preservedUnnamedBlocks > 0) {
            log.info("Preserved {} valid source blocks whose original filenames are unknown.", preservedUnnamedBlocks);
        }

        ArrayList<String> visibleFakeFiles = new ArrayList<>();
        // if (attributes != null) {
        // newFiles.add("(attributes)");
        // // Only generate attributes file when there has been one before
        // AttributesFile attributesFile = new AttributesFile(newFiles.size());
        // // Generate new values
        // long time = (new Date().getTime() + 11644473600000L) * 10000L;
        // for (int i = 0; i < newFiles.size() - 1; i++) {
        // String name = newFiles.get(i);
        // int entry = attributes.getEntry(name);
        // if (newFileMap.containsKey(name)){
        // // new file
        // attributesFile.setEntry(i, getCrc32(newFileMap.get(name)), time);
        // }else if (entry >= 0) {
        // // has timestamp
        // attributesFile.setEntry(i, getCrc32(name),
        // attributes.getTimestamps()[entry]);
        // } else {
        // // doesnt have timestamp
        // attributesFile.setEntry(i, getCrc32(name), time);
        // }
        // }
        // // newfiles don't contain the attributes file yet, hence -1
        // System.out.println("added attributes");
        // byte[] attrArr = attributesFile.buildFile();
        // fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos,
        // attrArr.length);
        // newBlock = new Block(currentPos - headerOffset, 0, 0, EXISTS |
        // COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
        // newBlocks.add(newBlock);
        // MpqFile.writeFileAndBlock(attrArr, newBlock, fileWriter,
        // newDiscBlockSize, "(attributes)");
        // currentPos += newBlock.getCompressedSize();
        // }

        if (fakeFilesCount < 0) {
            fakeFilesCount = calculateMaxFakeFilesCount(currentPos - newArchiveBase, newFiles.size() + (buildListfile ? 1 : 0), newBlocks.size());
            log.info("Calculated {} fake batches for max V0 table output.", fakeFilesCount);
        }

        if (fakeFilesCount > 0) {
            ArrayList<Block> fakeBlocks = new ArrayList<>();
            ArrayList<String> fakeFiles = new ArrayList<>();

            log.info("Generating fake MPQ entries: batches={}, existingRealEntries={}, maxV0Tables={}",
                fakeFilesCount, newFiles.size(), maximizeV0Tables);

            if (newBlocks.size() > 0) {
                for (int i = 0; i < fakeFilesCount; i++) {
                    Block block = newBlocks.get((int) (Math.random() * newBlocks.size()));
                    int offset = (int) (Math.random() * 10 - 5);
                    if (offset == 0) {
                        offset = 4;
                    }
                    Block newBlock = new Block(block.getFilePos() + offset, block.getCompressedSize(), block.getNormalSize(), block.getFlags());
                    fakeBlocks.add(newBlock);

                    fakeFiles.add(buildUniqueFakeVisiblePath("offset", i, usedFileKeys));
                    logFakeProgress("offset fake entries", i + 1, fakeFilesCount);
                }

                for (int i = 0; i < fakeFilesCount; i++) {
                    Block block = newBlocks.get((int) (Math.random() * newBlocks.size()));
                    int offset = (int) (Math.random() * 10 - 5);
                    Block newBlock = new Block(block.getFilePos(), block.getCompressedSize() + offset, block.getNormalSize() + offset, block.getFlags());
                    fakeBlocks.add(newBlock);

                    fakeFiles.add(buildUniqueFakeVisiblePath("dupe", i, usedFileKeys));
                    logFakeProgress("duplicate fake entries", i + 1, fakeFilesCount);
                }
            }

            for (int i = 0; i < fakeFilesCount; i++) {
                int size = (int) (Math.random() * currentPos);
                int rand = (int) (Math.random() * 5);
                int flag = EXISTS;
                switch (rand) {
                    case 0:
                        flag |= COMPRESSED;
                        break;
                    case 1:
                        flag |= ENCRYPTED;
                        break;
                    case 2:
                        flag |= ENCRYPTED | ADJUSTED_ENCRYPTED;
                        break;
                    case 3:
                        flag |= ENCRYPTED | COMPRESSED;
                        break;
                    case 4:
                        flag |= ENCRYPTED | ADJUSTED_ENCRYPTED | COMPRESSED;
                        break;
                }

                Block newBlock = new Block(currentPos - size, (int) (size * Math.random()), size, flag);
                fakeBlocks.add(newBlock);
                fakeFiles.add(buildUniqueFakeVisiblePath("asset", i, usedFileKeys));
                logFakeProgress("asset fake entries", i + 1, fakeFilesCount);
            }

            if (hasFile("war3map.j")) {
                int pos = hashTable.getBlockIndexOfFile("war3map.j");
                Block copyBlock = blockTable.getBlockAtPos(pos);
                fakeBlocks.add(copyBlock);
                fakeFiles.add(buildUniqueLookalikePath("scripts\\war3map.j", usedFileKeys));
            }

            int padIndex = 0;
            int padTotal = WARCRAFT_V0_MAX_FILE_ENTRIES - newFiles.size() - fakeFiles.size() - (buildListfile ? 1 : 0);
            while (maximizeV0Tables && newFiles.size() + fakeFiles.size() + (buildListfile ? 1 : 0) < WARCRAFT_V0_MAX_FILE_ENTRIES) {
                fakeBlocks.add(new Block(Math.max(0, currentPos - 1), 0, 0, EXISTS | SINGLE_UNIT));
                fakeFiles.add(buildUniqueFakeVisiblePath("pad", padIndex++, usedFileKeys));
                logFakeProgress("padding fake entries", padIndex, padTotal);
            }

            visibleFakeFiles.addAll(fakeFiles);
            newBlocks.addAll(fakeBlocks);
            newFiles.addAll(fakeFiles);
            log.info("Generated {} fake file names and {} fake block entries.", fakeFiles.size(), fakeBlocks.size());

            long seed = System.nanoTime();
            Collections.shuffle(newFiles, new Random(seed));
            Collections.shuffle(newBlocks, new Random(seed));
            log.info("Shuffled rebuilt file and block tables.");
        }

        if (buildListfile && !listFile.getFiles().isEmpty()) {
            newFiles.add("(listfile)");
            byte[] listfileArr = maximizeV0Tables ? buildFakeListfile(visibleFakeFiles) : listFile.asByteArray();
            log.info("Writing {} listfile with {} visible fake entries ({} bytes).",
                maximizeV0Tables ? "fake-only" : "normal", visibleFakeFiles.size(), listfileArr.length);
            Block newBlock = new Block(currentPos - newArchiveBase, 0, 0, EXISTS | COMPRESSED | ENCRYPTED | ADJUSTED_ENCRYPTED);
            newBlocks.add(newBlock);
            int sectorCount = (int) Math.ceil((double) listfileArr.length / newDiscBlockSize);
            int worst = sectorCount * 4 + listfileArr.length + sectorCount + 16; // slack
            ByteBuffer fileWriter = ByteBuffer.allocate(worst).order(ByteOrder.LITTLE_ENDIAN);
            MpqFile.writeFileAndBlock(listfileArr, newBlock, fileWriter, newDiscBlockSize, "(listfile)", options);
            currentPos += newBlock.getCompressedSize();
            output.put(fileWriter.array(), 0, newBlock.getCompressedSize());
            log.debug("Added listfile");
        }

        int target = newFiles.size() + 2;
        int current = nextPowerOfTwo(target);
        newHashSize = maximizeV0Tables ? WARCRAFT_V0_HASH_TABLE_SIZE : Math.toIntExact(Math.multiplyExact((long) current, 2L));
        newBlockSize = newFiles.size() + 2;
        validateV0TableSizes(newHashSize, newBlockSize);

        newHashPos = currentPos - newArchiveBase;
        newBlockPos = newHashPos + newHashSize * 16L;
        log.info("Building tables: files={}, blocks={}, hashSlots={}, blockSlots={}.",
            newFiles.size(), newBlocks.size(), newHashSize, newBlockSize);

        // generate new hash table
        final int hashSize = newHashSize;
        HashTable hashTable = new HashTable(hashSize);
        int blockIndex = 0;
        for (String file : newFiles) {
            hashTable.setFileBlockIndex(file, HashTable.DEFAULT_LOCALE, blockIndex++);
            if (blockIndex % 10000 == 0 || blockIndex == newFiles.size()) {
                log.info("Inserted {} / {} names into rebuilt hash table.", blockIndex, newFiles.size());
            }
        }

        // prepare hashtable for writing
        final ByteBuffer hashTableBuffer = ByteBuffer.allocate(hashSize * 16);
        hashTable.writeToBuffer(hashTableBuffer);
        hashTableBuffer.flip();

        // encrypt hash table
        final MPQEncryption encrypt = new MPQEncryption(KEY_HASH_TABLE, false);
        encrypt.processSingle(hashTableBuffer);
        hashTableBuffer.flip();

        // write out hash table
        output.put(hashTableBuffer);

        // write out block table
        ByteBuffer blocktableWriter = ByteBuffer.allocate(Math.toIntExact((newBlockSize * 16L)));
        blocktableWriter.order(ByteOrder.LITTLE_ENDIAN);
        BlockTable.writeNewBlocktable(newBlocks, newBlockSize, blocktableWriter);

        output.put(blocktableWriter.array(), 0, blocktableWriter.position());

        currentPos += newHashSize * 16L + newBlockSize * 16L;

        newArchiveSize = currentPos - newArchiveBase;
        if (newFormatVersion == 0 && newArchiveSize > V0_MAX_ARCHIVE_SIZE) {
            throw new JMpqException("V0 archive size exceeds unsigned 32-bit range: " + newArchiveSize);
        }

        ByteBuffer headerWriter = ByteBuffer.allocate(Math.toIntExact((newHeaderSize + 4L)));
        headerWriter.order(ByteOrder.LITTLE_ENDIAN);
        writeHeader(headerWriter);

        int size = output.position();
        output.position(Math.toIntExact(newArchiveBase + 4));
        output.put(headerWriter.array(), 0, headerWriter.position());



        outputByteArray = new byte[size];
        System.arraycopy(output.array(), 0, outputByteArray, 0, size);

        fc.close();

        t = System.nanoTime() - t;
        log.info("Rebuild complete: {} bytes, took {}ms.", size, (t / 1000000));
    }

    public byte[] getOutputByteArray() throws IOException {
        return outputByteArray;
    }

    private void sortListfileEntries(ArrayList<String> remainingFiles) {
        // Sort entries to preserve block table order
        remainingFiles.sort((o1, o2) -> {
            int pos1 = 999999999;
            int pos2 = 999999999;
            try {
                pos1 = hashTable.getBlockIndexOfFile(o1);
            } catch (IOException ignored) {
            }
            try {
                pos2 = hashTable.getBlockIndexOfFile(o2);
            } catch (IOException ignored) {
            }
            return pos1 - pos2;
        });
    }

    /**
     * Utility method to fill a buffer from the given channel.
     *
     * @param buffer buffer to fill.
     * @param src    channel to fill from.
     * @throws IOException  if an exception occurs when reading.
     * @throws EOFException if EoF is encountered before buffer is full or channel is non
     *                      blocking.
     */
    private static void readFully(ByteBuffer buffer, ReadableByteChannel src) throws IOException {
        while (buffer.hasRemaining()) {
            if (src.read(buffer) < 1)
                throw new EOFException("Cannot read enough bytes.");
        }
    }

    /**
     * Utility method to write out a buffer to the given channel.
     *
     * @param buffer buffer to write out.
     * @param dest   channel to write to.
     * @throws IOException if an exception occurs when writing.
     */
    private static void writeFully(ByteBuffer buffer, WritableByteChannel dest) throws IOException {
        if (!buffer.hasRemaining()) {
            throw new EOFException("Trying to write empty buffer.");
        }
        while (buffer.hasRemaining()) {
            if (dest.write(buffer) < 1)
                throw new EOFException("Cannot write enough bytes.");
        }
    }

    /**
     * @return Whether the map can be modified or not
     */
    public boolean isCanWrite() {
        return canWrite;
    }

    /**
     * Whether or not to keep the data before the actual mpq in the file
     *
     * @param keepHeaderOffset
     */
    public void setKeepHeaderOffset(boolean keepHeaderOffset) {
        this.keepHeaderOffset = keepHeaderOffset;
    }


    /**
     * Get block table block table.
     *
     * @return the block table
     */
    public BlockTable getBlockTable() {
        return blockTable;
    }

    public HashTable getHashTable() {
        return hashTable;
    }

    /**
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize + ", formatVersion=" + formatVersion + ", discBlockSize=" + discBlockSize
            + ", hashPos=" + hashPos + ", blockPos=" + blockPos + ", hashSize=" + hashSize + ", blockSize=" + blockSize + ", hashMap=" + hashTable + "]";
    }

    /**
     * Returns an unmodifiable collection of all Listfile entries
     *
     * @return Listfile entries
     */
    public Collection<String> getListfileEntries() {
        return Collections.unmodifiableCollection(listFile.getFiles());
    }
}
