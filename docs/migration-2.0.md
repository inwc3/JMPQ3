# Migrating from JMPQ3 1.x to 2.0

JMPQ3 2.0 requires Java 25 for every usage, including archives supplied as
`byte[]` and results returned as `byte[]`. The 1.x API still works as a
deprecated facade, so existing code can migrate in stages.

The most important change is the write lifecycle: reading an archive no longer
rebuilds it when `close()` runs. Use `MpqArchive` to read, then use
`MpqArchiveWriter` and an explicit `save(...)` or `toByteArray()` call to write.

## Coordinates and packages

| | 1.x | 2.0 |
|---|---|---|
| Maven group | `systems.crigges` | `org.inwc3` |
| New core | - | `org.inwc3.jmpq` |
| Deprecated facade | `systems.crigges.jmpq3` | unchanged, still importable |
| Java baseline | 11 | 25 |

JitPack consumers using `com.github.inwc3:JMPQ3` are unaffected. Anything
resolving the Maven coordinate directly needs the new group.

## The shape of the new API

Reading and writing are separate types, and writing happens when you ask for it:

```java
// read
try (MpqArchive archive = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
    byte[] script = archive.read("war3map.j");
    for (String name : archive.names()) { ... }
}

// write
byte[] image;
try (MpqArchive source = MpqArchive.open(path, MpqOpenOptions.warcraft3())) {
    image = MpqArchiveWriter.from(source, MpqWriteOptions.defaults())
        .put("war3map.j", newScript)
        .toByteArray();
}
Files.write(path, image);
```

The build happens while the source is open, because content is read from it, and
the write happens after it is closed, because a mapped file cannot be replaced
on Windows.

## Method mapping

| 1.x `JMpqEditor` | 2.0 |
|---|---|
| `new JMpqEditor(path, FORCE_V0)` | `MpqArchive.open(path, MpqOpenOptions.warcraft3())` |
| `new JMpqEditor(path, READ_ONLY)` | `MpqArchive.open(path, MpqOpenOptions.defaults())` |
| `hasFile(name)` | `MpqArchive.contains(name)` |
| `hasFile(name, locale)` | `MpqArchive.contains(name, locale)` |
| `extractFileAsBytes(name)` | `MpqArchive.read(name)` |
| `extractFile(name, stream)` | `MpqArchive.readTo(name, stream)` |
| `extractFileAsString(name)` | `new String(archive.read(name), UTF_8)` |
| `getFileNames()` | `MpqArchive.names()` |
| `getTotalFileCount()` | `MpqArchive.blockCount()` |
| `getMpqFile(name)` | `MpqArchive.entry(name)` then `read(entry)` |
| `getBlockTable()`, `getHashTable()` | `MpqArchive.entries()` |
| `insertByteArray(name, bytes)` | `MpqArchiveWriter.put(name, bytes)` |
| `insertFile(name, file)` | `MpqArchiveWriter.put(name, path)` |
| `deleteFile(name)` | `MpqArchiveWriter.remove(name)` |
| `close()` rebuilding the archive | `MpqArchiveWriter.save(path)` or `toByteArray()` |
| `getOutputByteArray()` | `MpqArchiveWriter.toByteArray()` |
| `setKeepHeaderOffset(false)` | `MpqWriteOptions.withPrefix(false)` |
| `close(buildListfile, ...)` | `MpqWriteOptions.withListfile(...)` |
| `RecompressOptions.newSectorSizeShift` | `MpqWriteOptions.withSectorSizeShift(...)` |

## Behaviour differences worth knowing

**Nothing is written unless you ask.** The facade rebuilds on `close()`. The core
does not: `save` is a separate call. That is the single biggest difference, and
the reason the split exists - a read can no longer rewrite a file.

**The format version is chosen, not inherited.** `MpqWriteOptions` accepts
version 0 or 1 and refuses the rest at construction. 1.x inherited whatever it
read and then emitted a version 0 header body for it.

**Sector size changes force a re-encode.** A file can only keep its stored bytes
when the target archive keeps the source's sector size, because a sector offset
table is expressed in that sector size. The writer works this out; 1.x copied
regardless and silently corrupted the result.

**Locales are first class.** One path can exist under several locales, and both
sides know it: `MpqArchive.localesOf(name)`, and `put`/`remove`/`contains`
overloads taking a locale. 1.x registered everything as neutral and a rebuild
dropped all but one variant.

**An archive that cannot enumerate itself says so.** `MpqArchive.isEnumerable()`
and `filesLostOnRebuild()` replace a log warning, so you can find out before a
rebuild how many files it would drop. Use `filesLostOnRebuild()`, not the
deprecated `unnamedBlockCount()`: the latter counts only nameless blocks, so it
reports zero for an archive whose `(attributes)` file a rebuild is about to
discard.

**Malformed headers are repaired, not rejected.** A garbage header size is
replaced by the version's real size and the archive opens with
`header().malformed()` set, matching what Storm.dll does. 1.x refused unless you
passed `FORCE_V0`.

**Hi-block tables are read.** Archives placing file data beyond 4 GiB have their
file positions extended from the hi-block table, as StormLib does. A hi-block
table that falls outside the file is ignored and the archive flagged malformed,
rather than refused.

**Sector checksums are verified by default.** Where an archive records an
Adler-32 per sector, a mismatch now fails the read instead of returning bytes
known to be wrong. 1.x ignored the checksums entirely. If you would rather
recover what is still intact from a damaged archive, turn it off:

```java
MpqArchive.open(path, MpqOpenOptions.defaults()
    .withSectorChecksumVerification(false));
```

**Attributes are parsed properly.** `archive.attributes()` returns a
`MpqAttributes` honouring the file's own bytemask, so archives carrying MD5
digests or patch bits are read rather than misread. The 1.x `AttributesFile`
assumed one fixed layout and reported one entry fewer than the file held.

## Recording metadata on write

Both are opt-in, because both change the bytes of every file written and
neither is needed for a valid archive. Warcraft III wants neither; StormLib
normally writes both.

```java
MpqWriteOptions.defaults()
    .withSectorChecksums(true)                     // Adler-32 per sector
    .withAttributes(true)                          // generate (attributes)
    .withAttributesTimestamp(buildTimestampMillis) // pin it, or the build is not reproducible
```

Two things to know. Generating `(attributes)` requires a CRC32 over each file's
decoded content, so it forces a decode of files that would otherwise have been
copied verbatim - enabling it costs real time on a large rebuild. And supplying
your own `(attributes)` while asking for generation is refused rather than
producing two entries under one name; supplying it alone stays legal, which is
how you preserved it before generation existed.
