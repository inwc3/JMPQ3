package systems.crigges.jmpq;

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
 */
public record MpqOpenOptions(boolean forceV0, short defaultLocale) {
    /** The neutral locale, used when an archive stores no localised variants. */
    public static final short NEUTRAL_LOCALE = 0;

    /**
     * @return options that trust the header and prefer the neutral locale.
     */
    public static MpqOpenOptions defaults() {
        return new MpqOpenOptions(false, NEUTRAL_LOCALE);
    }

    /**
     * Options matching how Warcraft III reads its own maps: format version 0 is
     * forced, so a corrupted header size or version cannot stop the archive
     * from opening.
     *
     * @return options for reading Warcraft III maps.
     */
    public static MpqOpenOptions warcraft3() {
        return new MpqOpenOptions(true, NEUTRAL_LOCALE);
    }

    /**
     * @param locale locale to prefer.
     * @return a copy of these options preferring {@code locale}.
     */
    public MpqOpenOptions withLocale(short locale) {
        return new MpqOpenOptions(forceV0, locale);
    }

    /**
     * @param force whether to force format version 0.
     * @return a copy of these options with that setting.
     */
    public MpqOpenOptions withForceV0(boolean force) {
        return new MpqOpenOptions(force, defaultLocale);
    }
}
