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

## 3. Name normalisation

MPQ paths are case insensitive and `\`-separated, which falls directly out of
the hash: every generator upper-cases its input, and `/` and `\` hash to
different values.

*Decision:* one function, `MpqNames.canonical`, produces the form used for all
keying, lookup and deduplication. Case folding uses `Locale.ROOT`, because
`String.toUpperCase()` under a Turkish default locale maps `i` to `İ` (U+0130)
and would produce archives no other MPQ implementation can read.

The `(listfile)` keeps the caller's original casing for display; only identity
is folded.

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
