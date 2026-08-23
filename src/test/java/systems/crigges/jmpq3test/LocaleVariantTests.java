package systems.crigges.jmpq3test;

import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqFileEntry;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Archives holding several localised variants of one path.
 * <p>
 * MPQ identifies a file by its path <em>and</em> its locale, so one path can
 * appear several times. No shipped fixture does this - every one is neutral -
 * which is why two separate defects survived a golden-file suite that checks
 * hundreds of files:
 * <ul>
 * <li>{@code entries()} named blocks by looking each path up once, so every
 * variant but the resolved one was reported with no name and locale 0. That
 * misstates the locale, and leaves an encrypted variant unreadable through the
 * returned entry because its key derives from the name.</li>
 * <li>The writer keyed pending files on path alone and registered them all as
 * neutral, so a rebuild silently dropped all but one variant and relabelled the
 * survivor.</li>
 * </ul>
 */
public class LocaleVariantTests {

    private static final short NEUTRAL = 0;
    private static final short GERMAN = 0x407;
    private static final short FRENCH = 0x40C;

    private static final byte[] NEUTRAL_TEXT = "neutral".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GERMAN_TEXT = "deutsch".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FRENCH_TEXT = "francais".getBytes(StandardCharsets.UTF_8);

    /** Three variants of one path must all be written and all be readable. */
    @Test
    public void everyVariantSurvivesAWrite() throws IOException {
        final byte[] image = build();

        try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.read("war3map.wts", NEUTRAL), NEUTRAL_TEXT);
            Assert.assertEquals(archive.read("war3map.wts", GERMAN), GERMAN_TEXT);
            Assert.assertEquals(archive.read("war3map.wts", FRENCH), FRENCH_TEXT);
        }
    }

    /** The locales present must be reportable, not just one of them. */
    @Test
    public void localesArediscoverable() throws IOException {
        try (MpqArchive archive = MpqArchive.open(build(), MpqOpenOptions.defaults())) {
            final List<Short> locales = archive.localesOf("war3map.wts");
            Assert.assertEquals(locales.size(), 3, "found " + locales);
            Assert.assertTrue(locales.containsAll(List.of(NEUTRAL, GERMAN, FRENCH)), locales.toString());

            Assert.assertTrue(archive.localesOf("not-here.txt").isEmpty());
        }
    }

    /** Enumeration must report each block's real locale, and name all of them. */
    @Test
    public void entriesReportTheRealLocale() throws IOException {
        try (MpqArchive archive = MpqArchive.open(build(), MpqOpenOptions.defaults())) {
            final List<MpqFileEntry> variants = archive.entries().stream()
                .filter(entry -> entry.name().equals("war3map.wts"))
                .toList();

            Assert.assertEquals(variants.size(), 3, "entries() lost variants: " + variants);
            for (MpqFileEntry entry : variants) {
                Assert.assertFalse(entry.name().isEmpty(), "a variant came back unnamed");
            }
            final List<Short> locales = variants.stream().map(MpqFileEntry::locale).sorted().toList();
            Assert.assertEquals(locales, List.of(NEUTRAL, GERMAN, FRENCH).stream().sorted().toList());

            // And each entry must be readable through the entry itself, which
            // requires its name to have survived enumeration.
            for (MpqFileEntry entry : variants) {
                Assert.assertTrue(archive.read(entry).length > 0, "locale " + entry.locale());
            }
        }
    }

    /** A rebuild must carry every variant across, with its locale intact. */
    @Test
    public void rebuildPreservesEveryVariant() throws IOException {
        final byte[] rebuilt;
        try (MpqArchive source = MpqArchive.open(build(), MpqOpenOptions.defaults())) {
            rebuilt = MpqArchiveWriter.from(source, MpqWriteOptions.defaults()).toByteArray();
        }

        try (MpqArchive archive = MpqArchive.open(rebuilt, MpqOpenOptions.defaults())) {
            Assert.assertEquals(archive.localesOf("war3map.wts").size(), 3,
                "the rebuild dropped locale variants");
            Assert.assertEquals(archive.read("war3map.wts", NEUTRAL), NEUTRAL_TEXT);
            Assert.assertEquals(archive.read("war3map.wts", GERMAN), GERMAN_TEXT);
            Assert.assertEquals(archive.read("war3map.wts", FRENCH), FRENCH_TEXT);
            // The path appears once in the list file, however many locales it has.
            Assert.assertEquals(archive.names().stream()
                .filter(name -> name.equals("war3map.wts")).count(), 1L);
        }
    }

    /** Removing a path removes every variant; removing one leaves the rest. */
    @Test
    public void removalIsLocaleAware() throws IOException {
        try (MpqArchive source = MpqArchive.open(build(), MpqOpenOptions.defaults())) {
            final MpqArchiveWriter one = MpqArchiveWriter.from(source, MpqWriteOptions.defaults());
            Assert.assertTrue(one.remove("war3map.wts", GERMAN));
            Assert.assertTrue(one.contains("war3map.wts", NEUTRAL));
            Assert.assertFalse(one.contains("war3map.wts", GERMAN));

            try (MpqArchive archive = MpqArchive.open(one.toByteArray(), MpqOpenOptions.defaults())) {
                Assert.assertEquals(archive.localesOf("war3map.wts").size(), 2);
            }

            final MpqArchiveWriter all = MpqArchiveWriter.from(source, MpqWriteOptions.defaults());
            Assert.assertTrue(all.remove("war3map.wts"));
            Assert.assertFalse(all.contains("war3map.wts"));

            try (MpqArchive archive = MpqArchive.open(all.toByteArray(), MpqOpenOptions.defaults())) {
                Assert.assertTrue(archive.localesOf("war3map.wts").isEmpty());
            }
        }
    }

    /**
     * A lookup with no locale follows the format's preference order: the
     * requested locale, then neutral, then whatever is there.
     */
    @Test
    public void lookupPrefersTheRequestedThenNeutralLocale() throws IOException {
        try (MpqArchive archive = MpqArchive.open(build(), MpqOpenOptions.defaults())) {
            // Default options prefer neutral.
            Assert.assertEquals(archive.read("war3map.wts"), NEUTRAL_TEXT);
            // An absent locale falls back rather than failing.
            final short russian = 0x419;
            Assert.assertEquals(archive.read("war3map.wts", russian), NEUTRAL_TEXT);
        }
    }

    /** Builds an archive with one path under three locales, plus a plain file. */
    private static byte[] build() throws IOException {
        return MpqArchiveWriter.create(MpqWriteOptions.defaults())
            .put("war3map.wts", NEUTRAL, NEUTRAL_TEXT)
            .put("war3map.wts", GERMAN, GERMAN_TEXT)
            .put("war3map.wts", FRENCH, FRENCH_TEXT)
            .put("war3map.j", "script".getBytes(StandardCharsets.UTF_8))
            .toByteArray();
    }
}
