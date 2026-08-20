#!/usr/bin/env python3
"""Independent reference reader for MPQ archives.

This exists so the golden-file tests are not verifying JMPQ3 against itself.
It is a deliberately separate implementation, written from the StormLib source
(`SCompression.cpp`, `SBaseCommon.cpp`, `SBaseFileTable.cpp`) and Zezula's
format notes, sharing no code with the library under test.

Scope
-----
Fully decodes: stored, zlib, bzip2, sparse, and the multi-stage combinations of
those, applied in StormLib's ``dcmp_table`` order.

Not decoded: PKWARE ("implode"), Huffman and ADPCM. Porting three more codecs
here would buy little: JMPQ3's *writer* only ever emits zlib or stored sectors,
so round-trip verification of anything JMPQ3 produces is fully covered. Files
using the other codecs are reported with ``codec=unsupported`` and their
structure is still checked (sector offsets monotonic and within bounds), so a
malformed archive is still caught.

Commands
--------
inspect   dump header and block table for eyeballing
manifest  emit a manifest of every readable file: name, size, md5, flags
verify    check an archive against a manifest of expected name/size/md5

Manifests are tab separated and sorted, so they diff cleanly in review.
"""

import argparse
import bz2
import hashlib
import struct
import sys
import zlib
from pathlib import Path

# --------------------------------------------------------------------------
# Cryptography (StormLib InitializeMpqCryptography)
# --------------------------------------------------------------------------

MASK32 = 0xFFFFFFFF


def _make_crypt_table():
    table = [0] * 0x500
    seed = 0x00100001
    for i in range(0x100):
        index = i
        for _ in range(5):
            seed = (seed * 125 + 3) % 0x2AAAAB
            hi = (seed & 0xFFFF) << 16
            seed = (seed * 125 + 3) % 0x2AAAAB
            lo = seed & 0xFFFF
            table[index] = hi | lo
            index += 0x100
    return table


CRYPT_TABLE = _make_crypt_table()

HASH_TABLE_INDEX = 0x000
HASH_NAME_A = 0x100
HASH_NAME_B = 0x200
HASH_FILE_KEY = 0x300


def hash_string(text, hash_type):
    """MPQ string hash. Paths are upper-cased and '/' is folded to '\\'."""
    seed1 = 0x7FED7FED
    seed2 = 0xEEEEEEEE
    for char in text.replace("/", "\\").upper().encode("cp1252", "replace"):
        seed1 = CRYPT_TABLE[hash_type + char] ^ ((seed1 + seed2) & MASK32)
        seed2 = (char + seed1 + seed2 + (seed2 << 5) + 3) & MASK32
    return seed1 & MASK32


def decrypt(data, key):
    """Decrypt a whole number of dwords; a trailing partial dword is copied."""
    count = len(data) // 4
    out = bytearray()
    seed = 0xEEEEEEEE
    values = struct.unpack("<%dI" % count, data[: count * 4]) if count else ()
    for value in values:
        seed = (seed + CRYPT_TABLE[0x400 + (key & 0xFF)]) & MASK32
        plain = value ^ ((key + seed) & MASK32)
        key = (((~key << 0x15) & MASK32) + 0x11111111 | (key >> 0x0B)) & MASK32
        seed = (plain + seed + (seed << 5) + 3) & MASK32
        out += struct.pack("<I", plain)
    out += data[count * 4:]
    return bytes(out)


# --------------------------------------------------------------------------
# Flags
# --------------------------------------------------------------------------

FLAG_IMPLODE = 0x00000100
FLAG_COMPRESS = 0x00000200
FLAG_ENCRYPTED = 0x00010000
FLAG_KEY_V2 = 0x00020000
FLAG_SINGLE_UNIT = 0x01000000
FLAG_DELETE_MARKER = 0x02000000
FLAG_SECTOR_CRC = 0x04000000
FLAG_EXISTS = 0x80000000

FLAG_NAMES = [
    (FLAG_IMPLODE, "IMPLODE"),
    (FLAG_COMPRESS, "COMPRESS"),
    (FLAG_ENCRYPTED, "ENCRYPTED"),
    (FLAG_KEY_V2, "KEY_V2"),
    (FLAG_SINGLE_UNIT, "SINGLE_UNIT"),
    (FLAG_DELETE_MARKER, "DELETE_MARKER"),
    (FLAG_SECTOR_CRC, "SECTOR_CRC"),
    (FLAG_EXISTS, "EXISTS"),
]


def flags_to_text(flags):
    names = [name for mask, name in FLAG_NAMES if flags & mask]
    leftover = flags & ~sum(mask for mask, _ in FLAG_NAMES)
    if leftover:
        names.append("UNKNOWN_%08X" % leftover)
    return "|".join(names) if names else "NONE"


# --------------------------------------------------------------------------
# Sector decompression, StormLib SCompDecompressInternal / SCompDecompress2
# --------------------------------------------------------------------------

class Unsupported(Exception):
    """A codec this reference deliberately does not implement."""


class Corrupt(Exception):
    """The archive does not hold together."""


def _sparse(data, max_size):
    if len(data) < 5:
        raise Corrupt("sparse stream shorter than 5 bytes")
    declared = struct.unpack(">I", data[:4])[0]
    if declared > max_size:
        raise Corrupt("sparse declares %d, expected at most %d" % (declared, max_size))
    out = bytearray()
    pos = 4
    while pos < len(data) and len(out) < declared:
        control = data[pos]
        pos += 1
        if control & 0x80:
            length = (control & 0x7F) + 1
            if pos + length > len(data):
                raise Corrupt("truncated sparse literal run")
            out += data[pos: pos + min(length, declared - len(out))]
            pos += length
        else:
            out += b"\x00" * min((control & 0x7F) + 3, declared - len(out))
    return bytes(out)


def _zlib(data, _max_size):
    try:
        return zlib.decompress(data)
    except zlib.error as exc:
        raise Corrupt("zlib: %s" % exc) from exc


def _bzip2(data, _max_size):
    try:
        return bz2.decompress(data)
    except (OSError, ValueError, EOFError) as exc:
        raise Corrupt("bzip2: %s" % exc) from exc


def _unsupported(name):
    def raiser(_data, _max_size):
        raise Unsupported(name)
    return raiser


# StormLib dcmp_table order. The order is load bearing: it decides the
# sequence in which stages are applied to a multi-compressed sector.
DCMP_TABLE = [
    (0x10, "bzip2", _bzip2),
    (0x08, "pkware", _unsupported("pkware")),
    (0x02, "zlib", _zlib),
    (0x01, "huffman", _unsupported("huffman")),
    (0x80, "adpcm_stereo", _unsupported("adpcm_stereo")),
    (0x40, "adpcm_mono", _unsupported("adpcm_mono")),
    (0x20, "sparse", _sparse),
]

# StormLib SCompDecompress2: exact match, used for format version 2 and above.
EXACT_TABLE = {
    0x02: ["zlib"],
    0x08: ["pkware"],
    0x10: ["bzip2"],
    0x12: ["lzma"],
    0x20: ["sparse"],
    0x22: ["zlib", "sparse"],
    0x30: ["bzip2", "sparse"],
    0x41: ["huffman", "adpcm_mono"],
    0x81: ["huffman", "adpcm_stereo"],
}

STAGE_BY_NAME = {name: fn for _mask, name, fn in DCMP_TABLE}
STAGE_BY_NAME["lzma"] = _unsupported("lzma")


def decompress_sector(sector, expected_size, format_version):
    """Decode one sector. Returns (bytes, codec description)."""
    if len(sector) == expected_size:
        return sector, "stored"
    if not sector:
        raise Corrupt("empty compressed sector")

    mask = sector[0]
    payload = sector[1:]

    if format_version >= 2:
        stage_names = EXACT_TABLE.get(mask)
        if stage_names is None:
            raise Corrupt("unknown v2+ compression type 0x%02X" % mask)
    else:
        stage_names = []
        unclaimed = mask
        for bit, name, _fn in DCMP_TABLE:
            if mask & bit:
                stage_names.append(name)
                unclaimed &= ~bit
        if not stage_names or unclaimed:
            raise Corrupt("unknown compression mask 0x%02X" % mask)

    current = payload
    for name in stage_names:
        current = STAGE_BY_NAME[name](current, expected_size)
    return current, "+".join(stage_names)


# --------------------------------------------------------------------------
# Archive
# --------------------------------------------------------------------------

HEADER_MAGIC = b"MPQ\x1a"
USER_DATA_MAGIC = b"MPQ\x1b"
HEADER_SIZES = {0: 32, 1: 44, 2: 68, 3: 208}


class Block:
    __slots__ = ("index", "file_pos", "compressed_size", "normal_size", "flags")

    def __init__(self, index, file_pos, compressed_size, normal_size, flags):
        self.index = index
        self.file_pos = file_pos
        self.compressed_size = compressed_size
        self.normal_size = normal_size
        self.flags = flags

    def has(self, flag):
        return (self.flags & flag) == flag

    def has_sector_table(self):
        return not self.has(FLAG_SINGLE_UNIT) and (
            self.has(FLAG_COMPRESS) or self.has(FLAG_IMPLODE))


class Archive:
    def __init__(self, path, force_v0=True):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        self.force_v0 = force_v0
        self._parse()

    # -- header ---------------------------------------------------------

    def _find_header(self):
        size = len(self.data)
        pos = 0
        while pos + 4 < size:
            sample = self.data[pos: pos + 4]
            if sample == HEADER_MAGIC:
                return pos
            if sample == USER_DATA_MAGIC and not self.force_v0:
                redirect = pos + struct.unpack_from("<I", self.data, pos + 8)[0]
                if redirect + 4 < size and self.data[redirect: redirect + 4] == HEADER_MAGIC:
                    return redirect
            pos += 0x200
        raise Corrupt("no MPQ header in %s" % self.path)

    def _parse(self):
        self.header_offset = self._find_header()
        base = self.header_offset

        (self.declared_header_size, self.declared_archive_size,
         version, sector_shift) = struct.unpack_from("<IIHH", self.data, base + 4)
        (hash_pos, block_pos, self.hash_count,
         block_count) = struct.unpack_from("<IIII", self.data, base + 16)

        self.format_version = 0 if self.force_v0 else version
        # StormLib: only the low byte of wSectorSize is used.
        self.sector_shift = sector_shift & 0xFF
        self.sector_size = 512 << self.sector_shift
        # StormLib masks the hash count; protectors set the top nibble.
        self.hash_count &= 0x0FFFFFFF

        self.hash_pos = hash_pos
        self.block_pos = block_pos

        archive_size = min(self.declared_archive_size, len(self.data) - base)
        # StormLib clamps a block table that runs past the file.
        limit = (len(self.data) - base - block_pos) // 16
        self.block_count = max(0, min(block_count, limit, (archive_size - block_pos) // 16))

        self._read_tables()

    def _read_tables(self):
        base = self.header_offset

        raw = self.data[base + self.hash_pos: base + self.hash_pos + self.hash_count * 16]
        if len(raw) < self.hash_count * 16:
            raise Corrupt("hash table truncated")
        plain = decrypt(raw, hash_string("(hash table)", HASH_FILE_KEY))
        self.hash_entries = []
        for i in range(self.hash_count):
            key_a, key_b, locale, _platform, block_index = struct.unpack_from(
                "<IIHHI", plain, i * 16)
            self.hash_entries.append((key_a, key_b, locale, block_index))

        raw = self.data[base + self.block_pos: base + self.block_pos + self.block_count * 16]
        if len(raw) < self.block_count * 16:
            raise Corrupt("block table truncated")
        plain = decrypt(raw, hash_string("(block table)", HASH_FILE_KEY))
        self.blocks = []
        for i in range(self.block_count):
            file_pos, comp, normal, flags = struct.unpack_from("<IIII", plain, i * 16)
            self.blocks.append(Block(i, file_pos, comp, normal, flags))

    # -- lookup ---------------------------------------------------------

    def block_index_of(self, name):
        """Locate a name, mirroring StormLib's probe and BLOCK_INDEX_MASK."""
        if not self.hash_count:
            return None
        start = hash_string(name, HASH_TABLE_INDEX) % self.hash_count
        key_a = hash_string(name, HASH_NAME_A)
        key_b = hash_string(name, HASH_NAME_B)
        index = start
        for _ in range(self.hash_count):
            entry_a, entry_b, _locale, block_index = self.hash_entries[index]
            if block_index == 0xFFFFFFFF:
                return None
            if (entry_a, entry_b) == (key_a, key_b):
                masked = block_index & 0x0FFFFFFF
                if masked < len(self.blocks):
                    return masked
            index = (index + 1) % self.hash_count
        return None

    def has_file(self, name):
        return self.block_index_of(name) is not None

    def listfile(self):
        """Names from the archive's own (listfile), or [] if unreadable.

        A few real archives store the list file with a codec this reference
        does not implement (implodedTest.w3x imploded its own list file).
        Enumeration then falls back to whatever names the caller supplies with
        --names; content digests stay independent either way, which is the
        property the golden tests rely on.
        """
        if not self.has_file("(listfile)"):
            return []
        try:
            raw, _codec = self.read_file("(listfile)")
        except (Unsupported, Corrupt) as exc:
            print("warning: %s: cannot read (listfile): %s"
                  % (self.path.name, exc), file=sys.stderr)
            return []
        names = []
        for line in raw.decode("utf-8", "replace").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
            line = line.strip()
            if line:
                names.append(line)
        return names

    # -- file content ---------------------------------------------------

    def _sector_key(self, name, block):
        base = hash_string(name.split("\\")[-1].split("/")[-1], HASH_FILE_KEY)
        if block.has(FLAG_KEY_V2):
            base = ((base + block.file_pos) ^ block.normal_size) & MASK32
        return base

    def read_file(self, name):
        """Return (content, codec description). Raises Unsupported/Corrupt."""
        index = self.block_index_of(name)
        if index is None:
            raise Corrupt("file not found: %s" % name)
        return self.read_block(self.blocks[index], name)

    def read_block(self, block, name):
        base = self.header_offset + block.file_pos
        raw = self.data[base: base + block.compressed_size]
        if len(raw) < block.compressed_size:
            raise Corrupt("block for %s truncated: %d of %d bytes"
                          % (name, len(raw), block.compressed_size))
        if block.normal_size == 0:
            return b"", "empty"

        encrypted = block.has(FLAG_ENCRYPTED)
        key = self._sector_key(name, block) if encrypted else 0

        if not block.has_sector_table():
            unit = decrypt(raw, key) if encrypted else raw
            if block.has(FLAG_IMPLODE):
                raise Unsupported("pkware")
            if block.has(FLAG_COMPRESS):
                content, codec = decompress_sector(
                    unit, block.normal_size, self.format_version)
            else:
                content, codec = unit, "stored"
            if len(content) != block.normal_size:
                raise Corrupt("%s decoded to %d bytes, expected %d"
                              % (name, len(content), block.normal_size))
            return content, codec

        data_sectors = -(-block.normal_size // self.sector_size)
        entries = data_sectors + 1 + (1 if block.has(FLAG_SECTOR_CRC) else 0)
        table_bytes = raw[: entries * 4]
        if len(table_bytes) < entries * 4:
            raise Corrupt("sector offset table for %s truncated" % name)
        if encrypted:
            table_bytes = decrypt(table_bytes, (key - 1) & MASK32)
        offsets = list(struct.unpack("<%dI" % entries, table_bytes))

        for i in range(len(offsets) - 1):
            if not (0 <= offsets[i] <= offsets[i + 1] <= block.compressed_size):
                raise Corrupt("%s sector %d spans [%d, %d) outside %d stored bytes"
                              % (name, i, offsets[i], offsets[i + 1], block.compressed_size))

        out = bytearray()
        codecs = set()
        remaining = block.normal_size
        for i in range(data_sectors):
            sector = raw[offsets[i]: offsets[i + 1]]
            if encrypted:
                sector = decrypt(sector, (key + i) & MASK32)
            expected = min(remaining, self.sector_size)
            if block.has(FLAG_IMPLODE):
                raise Unsupported("pkware")
            content, codec = decompress_sector(sector, expected, self.format_version)
            if len(content) != expected:
                raise Corrupt("%s sector %d decoded to %d bytes, expected %d"
                              % (name, i, len(content), expected))
            out += content
            codecs.add(codec)
            remaining -= expected

        return bytes(out), ",".join(sorted(codecs))

    # -- reporting ------------------------------------------------------

    def entries(self, extra_names=()):
        """Yield (name, size, md5, flags_text, codec) for every named file.

        ``md5`` is ``-`` and ``codec`` explains why when this reference cannot
        decode the content; the file's structure is still validated.

        :param extra_names: names to consider in addition to the archive's own
            list file, for archives whose list file cannot be read.
        """
        names = list(self.listfile())
        names.extend(extra_names)
        for internal in ("(listfile)", "(attributes)", "(signature)"):
            if self.has_file(internal):
                names.append(internal)

        # MPQ identity is case and separator insensitive, so two spellings of
        # one path are the same file and must not both appear. Collapse them and
        # pick the lexicographically smallest spelling, which keeps the output
        # byte-identical across platforms. Sorting on the folded key alone left
        # ties to be broken by set iteration order, which differs between
        # machines and made the manifest non-reproducible.
        chosen = {}
        for name in names:
            key = name.replace("/", "\\").upper()
            if key not in chosen or name < chosen[key]:
                chosen[key] = name

        for key in sorted(chosen):
            name = chosen[key]
            index = self.block_index_of(name)
            if index is None:
                continue
            block = self.blocks[index]
            try:
                content, codec = self.read_block(block, name)
                yield (name, len(content), hashlib.md5(content).hexdigest(),
                       flags_to_text(block.flags), codec)
            except Unsupported as exc:
                yield (name, block.normal_size, "-",
                       flags_to_text(block.flags), "unsupported:%s" % exc)
            except Corrupt as exc:
                # Distinct from "-": the content is decodable in principle but
                # does not hold together. Conflating the two would let a corrupt
                # archive pass verification as merely unverifiable.
                yield (name, block.normal_size, "!",
                       flags_to_text(block.flags), "corrupt:%s" % exc)


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------

MANIFEST_HEADER = "# archive\tname\tsize\tmd5\tflags\tcodec"


def cmd_inspect(args):
    for path in _expand(args.archives):
        archive = Archive(path, force_v0=not args.honour_version)
        print("== %s" % path.name)
        print("   header_offset=%#x declared_header_size=%d version=%d(effective %d)"
              % (archive.header_offset, archive.declared_header_size,
                 0 if archive.force_v0 else archive.format_version, archive.format_version))
        print("   sector=%d hash_count=%d block_count=%d declared_archive_size=%d file=%d"
              % (archive.sector_size, archive.hash_count, archive.block_count,
                 archive.declared_archive_size, len(archive.data)))
        for block in archive.blocks:
            if not block.flags:
                continue
            print("   [%3d] pos=%#010x comp=%-9d norm=%-9d %s"
                  % (block.index, block.file_pos, block.compressed_size,
                     block.normal_size, flags_to_text(block.flags)))
    return 0


def _load_names(path):
    if not path:
        return []
    return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


def cmd_manifest(args):
    extra = _load_names(args.names)
    lines = [MANIFEST_HEADER]
    for path in _expand(args.archives):
        archive = Archive(path, force_v0=not args.honour_version)
        for name, size, digest, flags, codec in archive.entries(extra):
            lines.append("%s\t%s\t%d\t%s\t%s\t%s" % (path.name, name, size, digest, flags, codec))
    text = "\n".join(lines) + "\n"
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8", newline="\n")
        print("wrote %s (%d entries)" % (args.output, len(lines) - 1), file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


def cmd_verify(args):
    expected = {}
    for line in Path(args.manifest).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        archive_name, name, size, digest = line.split("\t")[:4]
        expected.setdefault(archive_name, {})[name] = (int(size), digest)

    failures = []
    codecs = {}
    checked = 0
    skipped = 0
    for path in _expand(args.archives):
        wanted = expected.get(path.name)
        if wanted is None:
            failures.append("%s: no manifest entry" % path.name)
            continue
        try:
            archive = Archive(path, force_v0=not args.honour_version)
            actual = {}
            for name, size, digest, _flags, codec in archive.entries(wanted.keys()):
                actual[name] = (size, digest)
                codecs[(path.name, name)] = codec
        except Corrupt as exc:
            failures.append("%s: %s" % (path.name, exc))
            continue

        for name, (size, digest) in sorted(wanted.items()):
            if digest == "!":
                failures.append("%s: manifest records %s as corrupt" % (path.name, name))
                continue
            if digest == "-":
                skipped += 1
                continue  # caller could not supply an expectation
            got = actual.get(name)
            if got is None:
                failures.append("%s: missing %s" % (path.name, name))
            elif got[1] == "!":
                failures.append("%s: %s is corrupt: %s"
                                % (path.name, name, codecs.get((path.name, name), "unknown")))
            elif got[1] == "-":
                # Structure validated (sector offsets in range, sizes
                # consistent) but the content uses a codec this reference does
                # not implement, so the digest cannot be compared.
                skipped += 1
                if got[0] != size:
                    failures.append("%s: %s size %d, expected %d"
                                    % (path.name, name, got[0], size))
            elif got != (size, digest):
                failures.append("%s: %s expected size=%d md5=%s, got size=%d md5=%s"
                                % (path.name, name, size, digest, got[0], got[1]))
            else:
                checked += 1

    for failure in failures:
        print("FAIL %s" % failure, file=sys.stderr)
    print("verified %d files across %d archives, %d skipped (codec not implemented "
          "by this reference), %d failures"
          % (checked, len(_expand(args.archives)), skipped, len(failures)), file=sys.stderr)
    return 1 if failures else 0


# Known answers for the cryptography, so a broken crypt table cannot make the
# reference silently agree with a broken library. These are the same magic
# constants JMPQ3 hardcodes, arrived at here from the algorithm alone.
SELFTEST_FILE_KEYS = {
    "(hash table)": 0xC3AF3770,   # -1011927184 as int32
    "(block table)": 0xEC83B3A3,  # -326913117 as int32
    "(listfile)": 0x2D2F0A94,
    "(attributes)": 0x50E314AF,
}


def cmd_selftest(_args):
    failures = []
    for name, expected in SELFTEST_FILE_KEYS.items():
        actual = hash_string(name, HASH_FILE_KEY)
        if actual != expected:
            failures.append("file key for %s: expected 0x%08X, got 0x%08X"
                            % (name, expected, actual))

    # Case and separator folding.
    if hash_string("Units/Test.txt", HASH_NAME_A) != hash_string("UNITS" + chr(92) + "test.txt", HASH_NAME_A):
        failures.append("path folding is inconsistent")

    # Sanity only; the file-key answers above are what really pin the crypt
    # table, and every manifest run exercises decrypt against real tables.
    sample = bytes(range(32))
    once = decrypt(sample, 0x12345678)
    if once == sample:
        failures.append("decrypt was a no-op")
    if len(once) != len(sample):
        failures.append("decrypt changed the length")

    # Sparse decoder against a hand-built stream: a zero run of 4, then
    # the literals "AB". Control 0x01 means (1 & 0x7F) + 3 == 4 zero bytes;
    # control 0x81 means (1 & 0x7F) + 1 == 2 literal bytes follow.
    stream = struct.pack(">I", 6) + bytes([0x01, 0x81]) + b"AB"
    decoded = _sparse(stream, 6)
    if decoded != bytes(4) + b"AB":
        failures.append("sparse decoder produced %r" % decoded)

    for failure in failures:
        print("FAIL %s" % failure, file=sys.stderr)
    print("selftest: %d checks, %d failures"
          % (len(SELFTEST_FILE_KEYS) + 4, len(failures)), file=sys.stderr)
    return 1 if failures else 0


def _expand(patterns):
    """Resolve arguments to a list of files in a platform-independent order.

    Sorted by name as a string, never by Path: Path comparison folds case on
    Windows and does not on POSIX, so sorting Paths put the archives in a
    different order on each platform and made the manifest unreproducible.
    """
    paths = []
    for pattern in patterns:
        path = Path(pattern)
        if path.is_dir():
            paths.extend(_sorted_by_name(p for p in path.iterdir() if p.is_file()))
        elif any(ch in pattern for ch in "*?"):
            paths.extend(_sorted_by_name(Path().glob(pattern)))
        else:
            paths.append(path)
    return paths


def _sorted_by_name(paths):
    return sorted(paths, key=lambda p: p.name)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--honour-version", action="store_true",
                        help="trust the header's format version instead of "
                             "forcing version 0 the way Warcraft III does")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("inspect", help="dump header and block table")
    p.add_argument("archives", nargs="+")
    p.set_defaults(func=cmd_inspect)

    p = sub.add_parser("manifest", help="emit a content manifest")
    p.add_argument("archives", nargs="+")
    p.add_argument("-o", "--output")
    p.add_argument("--names", help="file of extra names to consider, for "
                                   "archives whose list file cannot be read")
    p.set_defaults(func=cmd_manifest)

    p = sub.add_parser("selftest", help="known-answer checks on the reference itself")
    p.set_defaults(func=cmd_selftest)

    p = sub.add_parser("verify", help="check archives against a manifest")
    p.add_argument("archives", nargs="+")
    p.add_argument("-m", "--manifest", required=True)
    p.set_defaults(func=cmd_verify)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except (Corrupt, Unsupported) as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
