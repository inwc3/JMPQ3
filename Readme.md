[![Build](https://github.com/inwc3/JMPQ3/actions/workflows/build.yml/badge.svg)](https://github.com/inwc3/JMPQ3/actions/workflows/build.yml) [![JitPack](https://jitpack.io/v/inwc3/JMPQ3.svg)](https://jitpack.io/#inwc3/JMPQ3) [![Coverage Status](https://coveralls.io/repos/github/inwc3/JMPQ3/badge.svg?branch=master)](https://coveralls.io/github/inwc3/JMPQ3?branch=master)

# JMPQ3

JMPQ3 is a Java library for reading and writing MPQ (MoPaQ) archives, including
the `.mpq`, `.w3m`, and `.w3x` files used by Warcraft III.

JMPQ3 2.0 is built for Java 25. The requirement applies even when you never
touch the file system: the core uses Java's Foreign Function and Memory API for
its common byte access layer. In return, archives can be opened from `byte[]`
and written back to `byte[]` or an `OutputStream`, with no temporary files.

## Requirements

| JMPQ3 release | Java |
|---|---|
| 2.0.x | 25 |
| 1.9.x and older | 11 |

Use a JDK 25 toolchain to compile and run 2.0.x. File-less use does not provide
a Java 11 compatibility path.

## Install

JitPack is the simplest distribution route:

```gradle
repositories {
    mavenCentral()
    maven { url = uri('https://jitpack.io') }
}

dependencies {
    implementation 'com.github.inwc3:JMPQ3:v2.0.0'
}
```

## File-less usage

Build an archive in memory, then open the resulting bytes. The input array is
not copied when opened, and the writer returns a new archive image.

```java
import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;

import java.nio.charset.StandardCharsets;

byte[] image = MpqArchiveWriter.create(MpqWriteOptions.defaults())
    .put("war3map.j", "function main takes nothing returns nothing\nendfunction"
        .getBytes(StandardCharsets.UTF_8))
    .toByteArray();

try (MpqArchive archive = MpqArchive.open(image, MpqOpenOptions.warcraft3())) {
    byte[] script = archive.read("war3map.j");
    System.out.println(new String(script, StandardCharsets.UTF_8));
}
```

Use `save(OutputStream)` when the result should go directly to a response,
archive stream, or another destination. `MpqArchive.open(byte[], options)`
does not mutate the supplied bytes.

## File-backed usage

Path-backed reads use a Java 25 memory mapping and release the mapping when the
archive is closed. Writing is explicit and atomic at the target path.

```java
import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqOpenOptions;

import java.nio.file.Path;

try (MpqArchive archive = MpqArchive.open(Path.of("MyMap.w3x"),
    MpqOpenOptions.warcraft3())) {
    if (archive.contains("war3map.j")) {
        archive.readTo("war3map.j", System.out);
    }
}
```

To rebuild an archive, keep the source open while the writer builds its image,
then save the result. A read never rewrites the source as a side effect.

```java
import org.inwc3.jmpq.MpqArchive;
import org.inwc3.jmpq.MpqArchiveWriter;
import org.inwc3.jmpq.MpqOpenOptions;
import org.inwc3.jmpq.MpqWriteOptions;

import java.nio.file.Path;

Path sourcePath = Path.of("MyMap.w3x");
try (MpqArchive source = MpqArchive.open(sourcePath, MpqOpenOptions.warcraft3())) {
    MpqArchiveWriter.from(source, MpqWriteOptions.defaults())
        .put("war3map.j", "updated script".getBytes())
        .save(Path.of("Rebuilt.w3x"));
}
```

## Performance

- File-backed archives map their bytes and parse the tables on open.
- Byte-array archives are read without an input copy.
- Writers size the output buffer from the planned contents instead of growing
  it repeatedly.
- Rebuilds copy stored file sectors when the sector size is unchanged. This is
  the fast path and preserves the source encoding.
- `MpqWriteOptions.defaults()` stores data and skips `(attributes)` generation.
  Use `recompressed()` or enable attributes only when output size or metadata
  requires it.

The writer assembles the complete image in memory. Plan for heap roughly equal
to the output archive, plus any input or replacement file arrays you retain.

## MPQ format support

| MPQ format | Read | Write |
|---|---|---|
| v0, classic 32-bit header | yes | yes |
| v1, 64-bit offsets and hi-block table | yes | yes |
| v2 and v3, classic tables present | yes | no |

HET/BET-only archives are not supported. Compression support includes the
formats used by Warcraft III, such as zlib, PKWARE implode, BZIP2, sparse,
LZMA for v2+, Huffman, and ADPCM.

## Migrating from JMPQ3 1.x to 2.0

The 1.x API remains available as a deprecated compatibility facade, but new
code should use `org.inwc3.jmpq`. The important changes are:

1. Upgrade the runtime and build toolchain from Java 11 to Java 25.
2. Change the Maven group from `systems.crigges` to `org.inwc3` when resolving
   the published artifact directly. JitPack coordinates remain unchanged.
3. Replace `JMpqEditor` with `MpqArchive` for reads and
   `MpqArchiveWriter` for writes.
4. Replace `close()`-based rebuilding with an explicit `save(...)` or
   `toByteArray()` call. Opening an archive to read it no longer writes it.
5. Choose the output MPQ format with `MpqWriteOptions`; v2.0 writes MPQ format
   v0 or v1 and reads newer formats through their classic tables.

Common mappings:

| 1.x | 2.0 |
|---|---|
| `hasFile(name)` | `archive.contains(name)` |
| `extractFileAsBytes(name)` | `archive.read(name)` |
| `extractFile(name, stream)` | `archive.readTo(name, stream)` |
| `getFileNames()` | `archive.names()` |
| `insertByteArray(name, bytes)` | `writer.put(name, bytes)` |
| `insertFile(name, file)` | `writer.put(name, path)` |
| `deleteFile(name)` | `writer.remove(name)` |
| `close()` rebuild | `writer.save(path)` or `writer.toByteArray()` |
| `getOutputByteArray()` | `writer.toByteArray()` |

The complete migration notes, including locale handling, checksums, attributes,
and data-loss checks for listfile-less archives, are in
[`docs/migration-2.0.md`](docs/migration-2.0.md).

## Limitations

- Writing is limited to MPQ format v0 and v1.
- Archives without a usable `(listfile)` cannot be fully enumerated or rebuilt
  without losing unnamed blocks. Check `filesLostOnRebuild()` first.
- Signature verification and PTCH archive support are not implemented.
- MPQ archives are built in memory and must fit the available heap.

Format decisions are recorded in [`docs/mpq-format-notes.md`](docs/mpq-format-notes.md)
and are checked against [StormLib](https://github.com/ladislav-zezula/StormLib).
