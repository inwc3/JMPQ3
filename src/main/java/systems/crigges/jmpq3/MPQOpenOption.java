package systems.crigges.jmpq3;

/**
 * Enum of possible MPQ open options supplied at creation time.
 */
public enum MPQOpenOption {
    /**
     * Open in read only mode. The archive file will not be modified. Operations
     * that require modifying the archive file will throw an exception.
     */
    READ_ONLY,
    /**
     * Force opening using MPQ format version 0. Newer MPQ format features and
     * checks are ignored. Can allow some purposely corrupted version 0 archive
     * files to be opened. Behaviour is undefined if used on archive files that
     * are formated using version 1 or newer.
     */
    FORCE_V0
}
