# MPQ format notes

Interpretation decisions taken by JMPQ3, with the StormLib source that justifies
them. Where the circulating "MoPaQ File Format 1.0" text conflicts with
StormLib, StormLib wins: it is what the games actually shipped against.

Reference checkout used while writing these notes:
[StormLib](https://github.com/ladislav-zezula/StormLib) `master`, plus
[Zezula's format page](http://www.zezula.net/en/mpq/mpqformat.html).

---

## 1. Version numbering: "v0" here is StormLib's "V1"

StormLib's constant names are offset by one from the value actually stored in
the header's `wFormatVersion` field:

| `wFormatVersion` | StormLib constant | Header size | This library calls it |
|---|---|---|---|
| 0 | `MPQ_FORMAT_VERSION_1` | `0x20` (32) | **v0** |
| 1 | `MPQ_FORMAT_VERSION_2` | `0x2C` (44) | **v1** |
| 2 | `MPQ_FORMAT_VERSION_3` | `0x44` (68) | **v2** |
| 3 | `MPQ_FORMAT_VERSION_4` | `0xD0` (208) | **v3** |

*Decision:* everything in this codebase — field names, javadoc, log messages —
uses the raw `wFormatVersion` value, so "format version 2" always means the
`0x44`-byte HET/BET-capable header. When quoting StormLib source the note says
so explicitly. Read support targets versions 0–3; write support targets 0 and 1.

Source: `StormLib.h:307-310`, `StormLib.h:517-520`.

---

## 2. Sector compression dispatch has two incompatible models

The leading byte of a compressed sector is interpreted differently depending on
the archive's format version. StormLib selects between them in
`SCompDecompressX` (`SCompression.cpp`):

### Format version 0 and 1 — bit mask (`SCompDecompressInternal`)

The byte is a mask. Every set bit must map to a known algorithm, otherwise the
sector is rejected with `ERROR_NOT_SUPPORTED`. The decompressors are applied in
the order they appear in StormLib's `dcmp_table`:

1. `0x10` BZIP2
2. `0x08` PKWARE
3. `0x02` ZLIB
4. `0x01` HUFFMAN
5. `0x80` ADPCM stereo
6. `0x40` ADPCM mono
7. `0x20` SPARSE

*Decision:* `CompressionType`'s declaration order **is** this table order, and
`CompressionUtil` iterates the enum. This is why `ADPCM|HUFFMAN` (`0x41`,
`0x81`) decodes Huffman first and ADPCM second, and why `SPARSE|ZLIB` (`0x22`)
decodes zlib first.

### Format version 2 and above — exact match (`SCompDecompress2`)

The byte is compared for equality against a closed set: `0x02`, `0x08`, `0x10`,
`0x12`, `0x20`, `0x22`, `0x30`, `0x41`, `0x81`. Anything else is a corrupt
sector.

### `0x12` is LZMA only for version 2+

`MPQ_COMPRESSION_LZMA` is `0x12`, and StormLib's own comment says it "is NOT a
combination of flags". In a version 0 or 1 archive the same byte legitimately
means `BZIP2 | ZLIB`, and `SCompDecompressInternal` decodes it as such.

*Decision:* LZMA is reachable only through the version 2+ exact-match path.
The pre-2.0 code tested `(type & 0x12) != 0`, which also fires for plain
deflate (`0x02`) and plain BZIP2 (`0x10`), so it rejected sectors it could
have read. Fixed; `CompressionType` deliberately has no LZMA constant so it can
never take part in mask dispatch.

Source: `SCompression.cpp` `dcmp_table`, `SCompDecompressInternal`,
`SCompDecompress2`, `SCompDecompressX`; `StormLib.h:278-286`.

### MPQ's LZMA blob layout

`LZMA_HEADER_SIZE` is `1 + LZMA_PROPS_SIZE + 8` = 14 bytes: one filter byte
(must be 0), five LZMA property bytes, then an eight byte little-endian
uncompressed size, then the raw LZMA1 stream.

*Decision:* bytes 1..13 are byte-for-byte the standard "lzma alone" header, so
JMPQ3 skips the filter byte and hands the remainder to
`org.tukaani.xz.LZMAInputStream` rather than reimplementing the decoder.

Source: `SCompression.cpp` `Decompress_LZMA`, `LZMA_HEADER_SIZE`.

### Sparse (run-length) stream layout

Big-endian 32-bit decompressed length, then control bytes: high bit set means
`(b & 0x7F) + 1` literal bytes follow; high bit clear means `(b & 0x7F) + 3`
zero bytes. Streams shorter than 5 bytes are rejected.

Source: `src/sparse/sparse.cpp` `DecompressSparse`.

### The whole-file `IMPLODED` flag is not part of this dispatch

`MPQ_FILE_IMPLODE` (`0x00000100`) means every sector is PKWARE imploded and
carries **no** leading type byte at all. It is handled by `SCompExplode`, not
`SCompDecompress`.

*Decision:* `CompressionUtil.explode` is the separate entry point for it.

---

## 3. Name normalisation: case folds, separators do not

MPQ paths are case insensitive, which falls directly out of the hash: every
generator upper-cases its input.

Separators are a different matter, and it is easy to get backwards. StormLib has
two hash functions:

| Function | Slash handling |
|---|---|
| `HashString` | folds `/` (0x2F) to `\` (0x5C) |
| `HashStringSlash` | "DON'T convert slash (0x2F) to backslash (0x5C)" |

`OpenArchiveFromStream` sets `ha->pfnHashString = HashStringSlash`
(`SFileOpenArchive.cpp:268`), so **every hash table lookup on an opened archive
uses the non-folding variant**. `HashString` is used for the file encryption key
(`DecryptFileKey`), and only after `GetPlainFileName` has stripped the directory
part — where a separator can no longer appear, making the folding moot.

*Decision:* `MpqNames.canonical` folds case only. `"dir/file"` and `"dirile"`
are different files and neither resolves under the other's name. An earlier
revision of this document claimed StormLib folds slashes and the code did so
unconditionally; that was wrong, and it silently renamed any archive entry
containing a forward slash, so the entry stopped resolving and a writable
rebuild dropped the file as a stale list file entry.

`MpqNames.baseFileKey` strips the directory at the last `\` *or* `/`, matching
`GetPlainFileName`, which treats both as separators for that purpose.

Case folding uses `Locale.ROOT`, because `String.toUpperCase()` under a Turkish
default locale maps `i` to `İ` (U+0130) and would produce archives no other MPQ
implementation can read.

The `(listfile)` stores names exactly as supplied; only identity is folded.

---

## 4. Sector offset table presence is decided by flags, not by size

*Decision:* a file has a sector offset table when it is `COMPRESSED` or
`IMPLODED` **and** not `SINGLE_UNIT`. The pre-2.0 code derived
`sectorCount = ceil(size / sectorSize) + 1` and then used `sectorCount == 1` as
a stand-in for "empty file", which conflated two unrelated conditions and made
zero-byte files a special case throughout the extraction path.

`SINGLE_UNIT` files hold one contiguous (optionally compressed) blob with no
offset table. Uncompressed, non-imploded files likewise have none.

---

## 5. Encrypted files and rebuilds

A file's sector key derives from its **file name only**, with the directory part
stripped. When `MPQ_FILE_KEY_V2` (`ADJUSTED_ENCRYPTED`, `0x00020000`) is set the
key additionally folds in the file's offset within the archive and its
uncompressed size, so moving a file invalidates its key.

*Decision (documenting long-standing behaviour):* a rebuild that relocates an
encrypted file decrypts its sectors and stores them **plain**, clearing
`ENCRYPTED` and `ADJUSTED_ENCRYPTED` on the new block. Re-encrypting at the new
position would be equally valid; storing plain is what JMPQ3 has always done and
what Warcraft III accepts. The behaviour was previously undocumented, and the
old code left the flags describing the sectors incorrectly in some paths.

---

## 6. Hash table indexing for malformed capacities

StormLib indexes the hash table with `hash & (dwHashTableSize - 1)`
(`HASH_INDEX_MASK`, `SBaseCommon.cpp:210`), which assumes a power-of-two
capacity. Real archives always have one; protected archives sometimes do not.

*Decision:* non-power-of-two capacities are accepted so such archives can be
opened, but the mask rule is used regardless rather than an unsigned remainder.
Whatever wrote such a table did so with the mask, so starting the probe from a
remainder-derived bucket would begin in the wrong place — and because probing
stops at the first unused bucket, files that are present could be reported
missing. `x & (n - 1) <= n - 1` for any positive `n`, so the mask is always in
range even when `n` is not a power of two.

---

## 7. Whitespace in list file entries is significant

*Decision:* parsing `(listfile)` removes the line terminator and nothing else.
A name's leading or trailing whitespace is part of what it hashes to, so
trimming it produces a name that no longer resolves to its hash table entry;
a writable rebuild would then discard the file as a stale list file entry.
Protected archives rely on this, planting entries that differ from a real name
only by a trailing space or a zero-width space.

Lines that are entirely whitespace are skipped, since they cannot name a file.

---

## 8. Truncated sparse streams are rejected

A sparse stream carries its decompressed length in its header. StormLib reports
that declared length as the output size regardless of how much the control runs
actually produced (`sparse.cpp` `DecompressSparse`).

*Decision:* JMPQ3 is stricter and rejects a stream whose control runs stop
short. Accepting it would hand back the missing tail as zeros at exactly the
length the caller expected, so the corruption would pass every downstream
check. Nothing is lost by being strict here: JMPQ3 never writes sparse sectors,
so the only streams affected are genuinely damaged.


## 9. Sector checksums are Adler-32 seeded with zero, not one

The flag is `MPQ_FILE_SECTOR_CRC` (`0x04000000`) and the StormLib field is
`SectorChksums`, so "CRC32" is the natural reading. It is wrong twice over.

StormLib computes the value with zlib's `adler32`, and it passes a seed of `0`:

```c
// SFileReadFile.cpp, ReadMpqSectors
DWORD dwAdlerExpected = hf->SectorChksums[dwIndex];
// We can only check sector CRC when it is not zero
// Neither can we check it if it is 0xFFFFFFFF.
if(dwAdlerExpected != 0 && dwAdlerExpected != 0xFFFFFFFF)
{
    dwAdlerValue = adler32(0, pbInSector, dwRawBytesInThisSector);
    if(dwAdlerValue != dwAdlerExpected)
        { dwErrCode = ERROR_CHECKSUM_ERROR; break; }
}

// SFileAddFile.cpp, on the write side
hf->SectorChksums[dwSectorIndex] = adler32(0, pbCompressed, nOutBuffer);
```

A *standard* Adler-32 starts its accumulators at `s1 = 1, s2 = 0`; seeding zlib
with `0` starts them at `s1 = 0, s2 = 0`. The two results differ by 1 in the low
half and by the byte count in the high half — for every input, without
exception.

**Decision.** `MpqChecksums.adler32` implements the seeded-zero form.
`java.util.zip.Adler32` cannot be used: it offers no way to seed, so it always
computes the standard variant.

This one is worth dwelling on, because no self-consistent test can catch it. A
reader and a writer that both use the standard seed agree with each other on
every archive they exchange, and disagree with every archive StormLib ever
wrote. It was caught only by `tools/mpqref.py`, which derives the value
independently, and it is the reason that cross-check is in CI rather than being
a one-off.

**Bytes covered.** Both quotes above take the checksum over the sector *as
stored, minus its encryption* — after decrypting, before decompressing, and
including the compression-type byte. The read and write sides therefore agree
without either needing to know how the sector was compressed.

**Absent values.** `0` and `0xFFFFFFFF` both mean "not recorded" and are skipped,
so a file may legitimately carry the flag and no usable checksums.


## 10. The checksum chunk is never encrypted, and is zlib compressed

The checksums live in a chunk after the data sectors, delimited by the last two
entries of the sector offset table — which is why a `SECTOR_CRC` file has one
more offset entry than sectors plus one.

Two properties are easy to get wrong:

- **Never encrypted**, even when every data sector is. StormLib writes it
  without encrypting, and loads it with `LoadMpqTable(..., 0, ...)` — key `0`,
  meaning no decryption. An encrypted file therefore has encrypted sectors and a
  plain checksum chunk side by side.
- **Zlib compressed** when that is smaller, detected the same way a sector's
  compression is: the stored length being shorter than the natural length of
  `sectorCount * 4` bytes.

**Decision.** `MpqFileReader.readSectorChecksums` and
`MpqSectorWriter.encodeChecksums` follow both. The verbatim-copy path
(`storedBytesDecrypted`) iterates only the *data* sectors when decrypting, and
deliberately leaves the final chunk alone: decrypting it there corrupted it, and
because the copy clears the encryption flags while keeping `SECTOR_CRC`, the
corruption became permanent in the rebuilt archive.


## 11. The `(attributes)` bytemask decides the layout, and several lengths are legal

```
0x00 u32  version, always 100
0x04 u32  bytemask
0x08      u32   crc32    [n]   when 0x01
          u64   fileTime [n]   when 0x02
          u8x16 md5      [n]   when 0x04
          bits  patch    [n]   when 0x08
```

`n` is the **block table** size, so the arrays are indexed by block index and
include the `(attributes)` file's own row — whose checksum cannot be computed
and is left `0`, which reads back as "not recorded".

Two things the pre-2.0 parser got wrong. It read the bytemask and then assumed
CRC32-plus-FILETIME regardless, so any file carrying MD5 digests was misread.
And it derived the count as `(length - 8) / 12 - 1`; the `- 1` has no basis in
the layout. Its likely origin is that StormLib *tolerates* an attributes file one
entry short — the tool that writes it is rarely the tool reading it — so
somebody met a short file and hardcoded the short case.

**Decision.** `MpqAttributes.parse` computes the expected length from the
declared bytemask and accepts either `n` or `n - 1` entries, reporting which via
`truncated()`. A length matching neither is an error rather than a guess. Bits
outside the four known ones are preserved in `flags()` but their arrays cannot be
located, so parsing stops after the known prefix — as StormLib does.

The patch-bit array is `(n + 6) / 8` bytes, which is StormLib's own formula: it
rounds up and then tolerates a spare byte, rather than the `(n + 7) / 8` you
would expect.


## 12. The hi-block table is plain; the hash and block tables may not be

The hi-block table supplies bits 32 to 47 of each file position, one `u16` per
block entry, combined as StormLib's `MAKE_OFFSET64(hi, low)`. StormLib's comment
in `BuildFileTable_Classic` is explicit: *"Load the hi-block table. It is not
encrypted, nor compressed."*

The hash and block tables are the opposite: always encrypted, and from format
version 3 optionally compressed. Nothing in the position fields says whether a
table is compressed — the version 3 header carries each table's *stored* length
separately, and a stored length shorter than the entry count implies is what
marks it compressed.

**Decision.** `MpqHeader.hashTableStoredSize` / `blockTableStoredSize` return the
stored length, and `isHashTableCompressed` / `isBlockTableCompressed` compare it
against the plain length. `MpqArchive.loadTable` decrypts and *then* decompresses,
which is the order StormLib's `LoadMpqTable` uses because the writer compresses
before encrypting.

A hi-block table whose position falls outside the file is dropped and the archive
flagged malformed, rather than refused: reading the low words alone is exactly
what a version 0 reader does, and the archive is otherwise fine.


## 13. Version 3 MD5 digests are reported, not enforced

A version 3 header carries six MD5 digests — header, hash table, block table,
hi-block table, HET and BET. StormLib checks them and reports the result; it does
not refuse the archive.

**Decision.** `MpqArchive.integrity()` returns `UNRECORDED`, `VERIFIED` or
`MISMATCHED`, and a mismatch is logged. Refusing to open would throw away an
archive whose tables may decode every file perfectly. An all-zero digest counts
as "not recorded" rather than as the digest of those bytes.

