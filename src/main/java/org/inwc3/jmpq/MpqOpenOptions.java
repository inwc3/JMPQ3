package org.inwc3.jmpq;

/**
 * How to interpret an archive when opening it.
 *
 * @param forceV0       read the archive the way Warcraft III does: treat it as
 *                      format version 0 whatever the header claims, and ignore
 *                      user data headers. Needed for archives whose header was
 *                      deliberately corrupted, and harmless for ordinary
 *                      Warcraft III maps.
 * @param defaultLocale locale preferred by lookups that do not name one. 0 is
 *                      the neutral default and is what almost every archive
 *                      uses.
 * @param verifySectorChecksums check each sector of a {@code SECTOR_CRC} file
 *                      against its recorded Adler-32 while decoding, and fail
 *                      the read on a mismatch rather than returning bytes known
 *                      to be wrong.
 */
public record MpqOpenOptions(
    boolean forceV0,
    short defaultLocale,
    boolean verifySectorChecksums) {

    /** The neutral locale, used when an archive stores no localised variants. */
    public static final short NEUTRAL_LOCALE = 0;

    /**
     * @return options that trust the header, prefer the neutral locale, and
     *         verify sector checksums where an archive records them.
     */
    public static MpqOpenOptions defaults() {
        return new MpqOpenOptions(false, NEUTRAL_LOCALE, true);
    }

    /**
     * Options matching how Warcraft III reads its own maps: format version 0 is
     * forced, so a corrupted header size or version cannot stop the archive
     * from opening.
     *
     * @return options for reading Warcraft III maps.
     */
    public static MpqOpenOptions warcraft3() {
        return new MpqOpenOptions(true, NEUTRAL_LOCALE, true);
    }

    /**
     * @param locale locale to prefer.
     * @return a copy of these options preferring {@code locale}.
     */
    public MpqOpenOptions withLocale(short locale) {
        return new MpqOpenOptions(forceV0, locale, verifySectorChecksums);
    }

    /**
     * @param force whether to force format version 0.
     * @return a copy of these options with that setting.
     */
    public MpqOpenOptions withForceV0(boolean force) {
        return new MpqOpenOptions(force, defaultLocale, verifySectorChecksums);
    }

    /**
     * Turning verification off makes a damaged archive readable, which is
     * occasionally what you want: recovering what is still intact beats
     * recovering nothing. It cannot make a sound archive read differently.
     *
     * @param verify whether to check recorded sector checksums.
     * @return a copy of these options with that setting.
     */
    public MpqOpenOptions withSectorChecksumVerification(boolean verify) {
        return new MpqOpenOptions(forceV0, defaultLocale, verify);
    }
}
