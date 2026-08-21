# Migrating to JMPQ3 2.0

The 1.x API still works. `JMpqEditor` is a deprecated facade over the new core
and behaves as it did, so existing code compiles and runs unchanged. This
describes what to move to when you are ready.

## Coordinates and packages

| | 1.x | 2.0 |
|---|---|---|
| Maven group | `systems.crigges` | `org.inwc3` |
| New core | — | `org.inwc3.jmpq` |
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
the reason the split exists — a read can no longer rewrite a file.

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
and `unnamedBlockCount()` replace a log warning, so you can find out before a
rebuild how many files it would drop.

**Malformed headers are repaired, not rejected.** A garbage header size is
replaced by the version's real size and the archive opens with
`header().malformed()` set, matching what Storm.dll does. 1.x refused unless you
passed `FORCE_V0`.

**Hi-block tables are refused.** Archives placing file data beyond 4 GiB are
rejected explicitly rather than misread. Support arrives with the version 2 to 4
read work.

## Protection tooling

The `w3p` branch's `fakeFilesCount` and "maximise tables" mode are not in the
core. The mechanisms they needed are:

```java
MpqWriteOptions.defaults()
    .withHashTableCapacity(0x10000)   // maximised version 0 table
    .withExtraBlockEntries(32)        // spare block slots
    .withListfile(false)              // not enumerable by name
```

Decoy entries are ordinary `put` calls; what they are called is the tool's
policy, not this library's.
