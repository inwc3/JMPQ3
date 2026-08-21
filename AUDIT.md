# JMPQ3 Cleanup Audit

Date: 2026-08-20 · Basis: full source review at commit `e28f699` · Purpose: task list for the upcoming modernisation pass.

## Scope decisions (confirmed with maintainer — do not re-litigate)

| Decision | Choice |
|---|---|
| API compatibility | New clean core API + `JMpqEditor` kept as thin **deprecated** compat adapter |
| Format versions | v0 + v1 **read/write** fully polished; v2–v4 **read-only** (HET/BET, 64-bit); no v2–v4 write |
| Java baseline | **Java 25** (bytecode target and toolchain). Justified over 21 by adopting the FFM API (`MemorySegment`/`Arena`) for the read-I/O layer; in-house consumers (WurstScript, w3protect) are on 25 already |
| Write model | **Memory-first, no temp files, concurrency-safe.** The temp-dir machinery was a workaround for a long-gone Java bug — remove it entirely. Unify the `w3p` branch's in-memory rebuild into core (see P1-8) |
| Protection features | w3protect's needs (fake files / maximised V0 tables, today `fakeFilesCount` on w3p) served via **extension hooks** (explicit table sizes, extra entries, listfile policy), not first-class core API |
| Extra features | `(attributes)` write support, sector CRC flag (0x04000000) read+optional write, locale-aware public API |
| Out of scope | Signature verification/signing, v2–v4 write, patch (PTCH) archives |

## Reference sources (validate against these, not old forum docs)

- StormLib source — the de-facto normative implementation: https://github.com/ladislav-zezula/StormLib
- Zezula format docs (maintained by StormLib author): http://www.zezula.net/en/mpq/mpqformat.html
- Modern cross-check implementation (Rust, HET/BET incl. v3+): https://github.com/danielsreichenbach/mopaq (merged into warcraft-rs)
- Treat the circulating "MoPaQ File Format 1.0.txt" as historical; where it conflicts with StormLib source, StormLib wins.
- Test fixtures: generate golden archives with StormLib (smpq/stormlib CLI) for round-trip verification of every version/flag combination.

## Open GitHub issues mapped into this audit

| Issue | Disposition |
|---|---|
| [#47](https://github.com/inwc3/JMPQ3/issues/47) fake MPQ headers (protected map) | → P2-5b, best-effort nice-to-have |
| [#46](https://github.com/inwc3/JMPQ3/issues/46) bad header size on 59/857 maps | → P2-5a, in scope |
| [#38](https://github.com/inwc3/JMPQ3/issues/38) open of missing file creates 0-byte file | → P0-14, in scope |
| [#27](https://github.com/inwc3/JMPQ3/issues/27) modularize the library | **Excluded by maintainer decision** — do not act on |
| [#11](https://github.com/inwc3/JMPQ3/issues/11) cannot handle file attributes | → P2-4, in scope |
| [#10](https://github.com/inwc3/JMPQ3/issues/10) command line functionality | → P5-4, optional separate module |

---

## Phase 0 — Correctness & safety fixes (independent of redesign, do first)

These are real bugs or hazards in behaviour that must not be carried into the new core.

- **P0-1 Shared temp dir wipe.** `JMpqEditor.setupTempDir()` (JMpqEditor.java:449) uses a global `%TMP%/jmpq` dir, stored in a `public static File tempDir`, and **deletes all files in it on every open** — races across instances/processes. Per maintainer decision the temp-file machinery is removed **entirely** (it was a workaround for a long-fixed Java bug): the rebuild happens in memory (P1-3/P1-8), and nothing in the library touches `java.io.tmpdir` or holds global mutable state.
- **P0-2 Static mutable compression state.** `JzLibHelper` (static `Inflater`/`Deflater`/`comp` buffer, explicitly "not thread-safe") and `CompressionUtil` (static `ADPCM`, `Huffman`, `zopfli`) corrupt data when two archives are processed concurrently. Make instances per-call or per-archive; document the thread-safety contract. The `w3p` branch already converted `JzLibHelper` to per-call `Inflater`/`Deflater` instances ("thread safety" commits `448ccad`, `25b34a2`) — port that as the starting point.
- **P0-3 Wrong LZMA flag test.** `FLAG_LMZA = 0x12` overlaps DEFLATE (0x02) | BZIP2 (0x10); `(type & 0x12) != 0` misfires (CompressionUtil.java:24,130). Sector compression byte semantics per StormLib: single-value types vs. combinable masks. Rewrite the dispatch table; add BZIP2 and SPARSE decompression (both trivial: commons-compress / simple RLE) instead of throwing.
- **P0-4 Identity-keyed filename map.** `filenameToData` is a `LinkedIdentityHashMap<String, Either>` — string **identity** keys. `deleteFile(name)` (JMpqEditor.java:835) only removes the entry if the caller passes the *same String instance* used at insert; otherwise the "deleted" file is silently re-added on close. Also `LinkedList.remove` is O(n). Replace with a `LinkedHashMap` keyed on a normalised name (see P1-4).
- **P0-5 Name normalisation is inconsistent.** MPQ paths are case-insensitive and `\`-separated (per MPQ hash which uppercases). `Listfile` dedupes by MPQ hash key, but `filenameToData` compares raw strings — `"Test"` vs `"teST"` diverge between the two structures. One canonical normalisation function used everywhere.
- **P0-6 Listfile is lossy and non-deterministic.** `Listfile` stores `HashMap<Long,String>` keyed by the 64-bit MPQ hash — silent data loss on key collision, non-deterministic `(listfile)` output order, and `asByteArray()` uses platform default charset (Listfile.java:50). Keep insertion order, write UTF-8 explicitly, detect collisions loudly.
- **P0-7 Rebuild silently decrypts encrypted files.** `MpqFile.writeFileAndBlock` (MpqFile.java:207) decrypts sectors of ENCRYPTED/ADJUSTED files and writes them back **without** the ENCRYPTED flag. Necessary because the key depends on file position, but it is undocumented behaviour — decide policy (re-encrypt at new position vs. store plain) and document it.
- **P0-8 Header/table field validation missing.** Untrusted values (`blockSize`, `hashSize`, sector offsets, SOT entries) flow directly into allocations and positions — a malformed archive can trigger huge allocations or garbage reads (`readBlockTable` allocates `blockSize * 16` unchecked). Validate all header/table invariants; fail with a diagnostic exception. Consider a fuzz test (Jazzer) as acceptance.
- **P0-9 Off-by-ones.** `BlockTable.getBlockAtPos` accepts `pos == size` (BlockTable.java:38, `>` should be `>=`). `newArchiveSize = currentPos + 1` (JMpqEditor.java:1096) writes a size one byte larger than the data — verify against StormLib and fix.
- **P0-10 `buildAttributes` parameter is ignored.** `close(buildListfile, buildAttributes, options)` never uses `buildAttributes`; generation code is commented out (JMpqEditor.java:1028-1060). Superseded by the Phase-2 attributes task, but the API must stop lying in the interim.
- **P0-11 Exceptions lose causes; exceptions as control flow.** `new JMpqException(path + ": " + e.getMessage())` discards stack traces (JMpqEditor.java:217); `readAttributesFile` swallows all exceptions; `hasFile` and `sortListfileEntries` use try/catch as lookup logic. `HashTable` already has a non-throwing `hasFile` — use it; add cause-preserving constructors.
- **P0-12 Streams closed by the library.** All `MpqFile.extract*` paths close the caller-supplied `OutputStream` (MpqFile.java:80-199). Never close what you didn't open.
- **P0-13 `insertByteArray` aliases the caller's array.** Mutations after insert change what gets written. Copy on insert (or document + defensive-copy in the new API).
- **P0-14 Opening a missing file creates it** *(issue [#38](https://github.com/inwc3/JMPQ3/issues/38))*. The writable open path passes `StandardOpenOption.CREATE` (JMpqEditor.java:211), so probing a non-existent path leaves a 0-byte file behind. Creating a new archive is now an explicit operation (`createEmptyArchive`), so the editor constructor should require the file to exist and throw before touching the filesystem.
- **P0-15 Zero-byte-file hack.** `sectorCount = ceil(size/sectorSize) + 1` plus the `sectorCount == 1` early-outs (MpqFile.java:42,66,81) conflate "has sector offset table" with "empty file". Model SOT presence from flags (SINGLE_UNIT, COMPRESSED) per spec instead.

## Phase 1 — New core API (the redesign)

- **P1-1 Package/module layout.** New package (suggestion: `systems.crigges.jmpq` or keep `jmpq3` with `v2` core subpackage — decide once at start). Old `JMpqEditor`/`MpqFile` signatures remain as deprecated adapters delegating to the new core; old behaviour quirks preserved only where WurstScript depends on them.
- **P1-2 Split the god class.** `JMpqEditor` (1212 lines) currently owns: header search/parse, table I/O, temp-dir management, listfile policy, rebuild orchestration, extraction, insertion, "old vs new" duplicated header fields. Target shape roughly:
  - `MpqHeader` / `MpqUserData` — immutable parsed header model (all versions), explicit `formatVersion`.
  - `MpqArchive` (read side) — open from `Path`/`SeekableByteChannel`/`byte[]`, list/has/open files, streaming extraction.
  - `MpqArchiveWriter` (or builder on `MpqArchive`) — explicit `save()`/`saveAs()`; **no rebuild-on-close**. `close()` releases resources only.
  - `MpqFileEntry` — name+locale+flags+sizes value object replacing raw `Block` exposure.
  - Rebuild pipeline as its own class, building in memory and emitting to a sink (see P1-3).
- **P1-3 Memory-first I/O, kill MappedByteBuffer writing.** Master's rebuild maps regions sized by guesses (`fileData.length * 2L`, JMpqEditor.java:1009) — the historical buffer-overflow bug class (see `newBlocksizeBufferOverflow` test) — and mapped buffers are never unmapped, locking files on Windows. Target design:
  - **Write path:** build fully in memory (the `w3p` branch's `DynamicByteBuffer` approach, or an equivalent growing-buffer utility) and emit to a *sink* abstraction — `Path`, `SeekableByteChannel`, `OutputStream`, or `byte[]` (w3p's `getOutputByteArray()` becomes a first-class output mode). Exact sizes are known after compression; no guessing, no temp files.
  - **Read path (Java 25 FFM):** `Path`-backed archives open as an `Arena`-scoped mapped `MemorySegment` — deterministic unmap on close (fixes the Windows file-lock problem for real), bounds-checked access, and scales to multi-GB v2–v4 archives without heap copies. `byte[]` sources wrap as a heap segment; the parsing code sees one `MemorySegment`-based reader either way.
- **P1-4 Single name-normalisation + hashing service.** One class produces canonical name, table offset hash, key1/key2, file key (incl. ADJUSTED). Today the keygen triple is re-derived inline in at least four places (MpqFile.java:322,340,360; HashTable).
- **P1-5 Locale-aware public API.** Expose `locale` (default 0) on insert/extract/delete/has. `HashTable` already supports it; today's public API hardcodes `DEFAULT_LOCALE`.
- **P1-6 Explicit write-format selection.** Writer targets v0 or v1 explicitly (v1 = 44-byte header, hi-word table offsets when >4 GiB). Today the version is inherited implicitly from whatever was read and v2/3 "write" emits a v0-shaped header body with wrong sizes (JMpqEditor.java:602,950).
- **P1-7 Behaviour contracts.** Document and test: what happens without `(listfile)` (today: silent read-only downgrade + log warning), incomplete listfile (blocks silently discarded on rebuild — today only warned at JMpqEditor.java:396), `setKeepHeaderOffset`, in-memory archives. Make failure modes explicit (exceptions or result types, not log lines).
- **P1-8 Unify the `w3p` branch (memory-first + fixes), then retire it.** `origin/w3p` (used by w3protect) is ~30 commits ahead in this area and contains fixes master never received. Port into the new core:
  - The in-memory rebuild via `DynamicByteBuffer` + `getOutputByteArray()` (superseded by the P1-3 sink API).
  - Bug fixes: fileWriter sizing (`c0d60d4`), byte-array output size (`b7f0a3b`), buffer overflow (`014de9c`), negative block size guard (`8664077`), endless-loop fix (`4b89817`), thread-safety commits, plus `HashTable`/`BlockTable` deltas — diff `master...origin/w3p` file by file rather than cherry-picking blindly.
  - The protection features (`close(..., fakeFilesCount)`, maximise-V0-tables) do **not** land as core API. Instead expose extension hooks: explicit hash/block table capacity control, ability to add extra (dummy) table entries, and a pluggable listfile policy — enough for w3protect to reimplement fake-file padding on top of 2.0. Acceptance: w3protect can rebase onto the new API and the `w3p` branch is deleted.

## Phase 2 — Format completeness

- **P2-1 Polish v0/v1 read+write.** Correct v1 header round-trip (hi-word hash/block positions, 64-bit `archiveSize` handling), `Block.filePos` kept as `long` end-to-end (today `getFilePos()` truncates to int — BlockTable.java:88), user-data header (`MPQ\x1B`) parsed into a model instead of skipped (JMpqEditor.java:516 TODO), header-search alignment verified against StormLib.
  - Done. `Block.filePos` is `long` throughout and the truncating `getFilePos()` is deprecated in favour of `getFilePosition()`; the user data header is modelled as `MpqUserData`, with its payload readable rather than discarded.
- **P2-2 v2–v4 read support.** 64-bit table offsets, hash/block table hi-word arrays, **HET/BET tables** (encrypted + compressed variants), v4 MD5 validation of header/tables, compressed block/hash tables. Acceptance: open and fully extract StormLib-generated v2, v3, v4 archives byte-identically.
  - Done via the classic tables: 64-bit and hi-word table offsets, the hi-block table (`MAKE_OFFSET64`, and unlike the other tables neither encrypted nor compressed), compressed hash/block tables detected from the version 3 stored-length fields, and the version 3 MD5 digests checked and reported through `MpqArchive.integrity()`. See format notes 12 and 13.
  - **Not verified against real fixtures.** The acceptance criterion asks for StormLib-generated v2/v3/v4 archives; there are none in the repository and no StormLib build available to produce them. What exists is exercised with synthetic fixtures, which is weaker evidence and should not be read as the criterion being met. Generating fixtures with `smpq`/StormLib is the outstanding work.
- **P2-2a HET/BET tables -- deferred** *(split out of P2-2)*. Not implemented. Two reasons, in order of weight. First, it is not needed for the acceptance criterion above: StormLib writes classic hash and block tables alongside HET/BET for v2-v4 archives, so extraction goes through the path that now works, and HET/BET is only *required* for an archive that omits the classic tables. Second, writing it blind is the failure mode this project keeps hitting -- an implementation derived from the spec, verified against a fixture built from the same reading of that spec, proves only self-consistency. Format note 9 is a worked example of that trap costing real correctness. Do this once a StormLib-generated fixture exists.
- **P2-3 Sector CRC flag (0x04000000).** Verify per-sector ADLER/CRC on read when flag present; option to emit on write. Wire into extraction pipeline, not bolted onto `MpqFile`.
  - Done, in `MpqFileReader` on read and `MpqSectorWriter` on write, opt-in via `MpqWriteOptions.withSectorChecksums`. The checksums are Adler-32 **seeded with zero** -- not CRC32, and not standard Adler-32; see format notes 9 and 10, which also record what that distinction cost. Verification is on by default when reading and can be turned off to recover damaged archives.
- **P2-4 `(attributes)` write support** *(issue [#11](https://github.com/inwc3/JMPQ3/issues/11))*. Honour the attributes bytemask properly on read (today hardcodes crc+timestamp layout and has a suspicious `-1` in the entry count — AttributesFile.java:38); regenerate CRC32+FILETIME on write when requested. Remove the dead commented block in `JMpqEditor.close`. Historical context from the issue thread: generation was disabled because CRC32 differed from StormLib for some `.wav` files — root cause is likely multi-compression handling (first sector of ADPCM-compressed wavs is not ADPCM-compressed since it holds the wav header). Fix alongside P2-6 and pin with a StormLib-golden CRC test. Acceptance for the issue itself: load + close `war3.mpq`-style archives without dropping `(attributes)` in a way the game rejects.
  - Done. `MpqAttributes` reads every array the bytemask declares and accepts the entry counts StormLib tolerates; the unexplained `-1` is gone from the deprecated parser too. Generation is opt-in via `MpqWriteOptions.withAttributes`, with a pinnable timestamp so builds stay reproducible. The `.wav` CRC32 concern from the issue thread does not arise: the checksum is taken over decoded content, and the multi-compression ordering it depended on was fixed in P2-6.
- **P2-5a Tolerant header parsing for real-world (protected) maps** *(issue [#46](https://github.com/inwc3/JMPQ3/issues/46))*. 59/857 sampled maps fail with "Bad header size": `readHeaderSize` hard-rejects `headerSize < 32 || > 208` (JMpqEditor.java:443) even though the game itself ignores the field for v0 archives. Mirror StormLib's leniency: derive the effective header size from the format version, clamp/ignore garbage values, and treat other header fields defensively (this also removes most of the need for consumers to pass `FORCE_V0`). Acceptance: the Forest Defense sample from the issue opens and extracts.
  - Done in Phase 0, as a side effect of modelling the header: `MpqHeader` repairs rather than rejects, following `ConvertMpqHeaderToFormat4`.
- **P2-5b Fake-header protection resilience — nice to have** *(issue [#47](https://github.com/inwc3/JMPQ3/issues/47))*. Some protected maps plant decoy `MPQ\x1A` headers so `searchHeader` either accepts a bogus one or gives up. Approach: on finding a candidate header, validate it (plausible table positions/sizes within file) and keep scanning on failure instead of committing to the first match. Best-effort only — full protected-map support is explicitly not a goal; skip if it destabilises normal parsing.
  - Done. The plausibility test now applies to every candidate -- archive headers and user-data redirects alike -- rather than only in `FORCE_V0` mode, and the first candidate is kept as a fallback, so the scan can only ever find a header where the old one found one, never fewer.
- **P2-6 Complete decompression matrix.** Add BZIP2, SPARSE, LZMA (xz dep is already on the classpath and unused), and correct multi-compression ordering (ADPCM+Huffman path exists; verify against StormLib order). Compression write side stays deflate (+ zopfli option), but the sector-type byte handling must be table-driven per spec.
  - Done in Phase 0: dispatch is table-driven off StormLib's `dcmp_table`, and format note 2 records why `0x12` cannot be tested as a mask.

## Phase 3 — Code hygiene & dependencies

- **P3-1 Logging.** `logback-classic` + `logback.xml` ship in the library's runtime deps/resources and hijack consumers' logging config. Keep `slf4j-api` only; move logback + config to `testRuntimeOnly`. Remove `DebugHelper.appendData`'s `printStackTrace`.
- **P3-2 Dependency audit.** `commons-compress` used only for `SeekableInMemoryByteChannel` (trivially self-implemented) — and will be needed for BZIP2 (P2-6), so decide once. Evaluate replacing unmaintained `jzlib` with `java.util.zip` (`Deflater`/`Inflater`) — the hand-rolled `zlibStoreLevel0` in CompressionUtil duplicates jzlib level-0 anyway; benchmark before/after. `xz` currently unused (see P2-6).
- **P3-3 Delete dead/duplicated code.** Commented-out `loadDefaultListFile` and attributes block; near-identical sector loops `extractCompressedBlock` vs `extractImplodedBlock` (MpqFile.java:94,150); triple keygen duplication (P1-4); `Either` union class → sealed interface or two-field record; unused `DefaultListfile.txt` decision (resource shipped but load path commented out).
- **P3-4 Java 25 modernisation.** Records for `Block`/header/bucket models, sealed types where useful, **pattern matching for switch + record patterns** (final since 21) for compression dispatch and per-version header handling, `SequencedCollection` for the ordered file maps, FFM `MemorySegment`/`Arena` in the read layer (P1-3). Replace the `ThreadLocal` `STORE_BUFFER` in CompressionUtil with per-call allocation or a `ScopedValue`. The Vector API stays **out** (still incubator in 25 — not acceptable for a library). Set toolchain and `options.release` to 25.
- **P3-5 Naming/typos sweep.** `getAllVaildBlocks`, "Invaild block position", `DegugHelperTests`, `FLAG_LMZA`, javadoc stubs like "the fc" / "the b" / auto-generated noise. Full javadoc on the new public API.

## Phase 4 — Tests & CI

- **P4-1 Golden-file round-trip suite.** For each supported version/flag combo: StormLib-generated fixture → open → extract-all → compare hashes; rebuild → reopen with both jmpq3 and (in CI, optionally) StormLib CLI → compare. Today `testRebuild`/`testRecompressBuild` assert nothing (MpqTests.java:161,215).
- **P4-2 Fix test infrastructure.** `getResource().getFile()` breaks on paths with spaces and inside jars — copy resources to a temp dir via streams. Tests currently mutate files inside `build/resources` and litter `out/` in the working dir. Remove `System.out.println`.
- **P4-3a Bound decompression output allocations** *(found during Phase 0 self-review)*. Header and table validation now rejects implausible table geometry, and the write path no longer preallocates from a header-supplied archive size. One vector remains: a block may declare an arbitrary `normalSize`, and each decompressor allocates its expected output size up front, so a small crafted archive can still force large per-sector allocations. Fixing it properly means having the codecs grow their output instead of preallocating, which wants the Jazzer fuzz harness to validate it — hence grouped here with P4-3 rather than done blind in Phase 0.
- **P4-3 New coverage needed.** Concurrency (two archives in parallel — pins P0-2), locale API, encrypted-file round-trip (incl. ADJUSTED key), sector CRC, v2–v4 fixtures, malformed-archive rejection (pins P0-8), empty file, file > one sector exactly at boundary, listfile ordering determinism.
- **P4-4 CI.** Only `gradle-publish.yml` exists (runs on release). Add a build+test workflow on push/PR (Windows + Linux matrix — path handling differs), publish jacoco report. Consider migrating TestNG → JUnit 5 while tests are being reworked (low priority, do only if touching most tests anyway).

## Phase 5 — Docs & packaging

- **P5-1 README.** There is none. Cover: what/why, quick-start for new API, migration table `JMpqEditor` → new API, supported format matrix (read/write per version/feature), thread-safety statement.
- **P5-2 Publishing coordinates.** `group 'systems.crigges'` vs publication `groupId 'inwc3'` inconsistency; version bump to 2.0.0 with the new API; verify jitpack.yml still matches the Java 25 toolchain.
- **P5-3 Format notes doc.** Short `docs/mpq-format-notes.md` recording the spec interpretations chosen (with StormLib source references) — this is what makes it a *reference* library.
- **P5-4 CLI tool — optional** *(issue [#10](https://github.com/inwc3/JMPQ3/issues/10))*. Small separate module/jar (list/extract/insert/rebuild, listfile + input/output dir options) wrapping the new API, per the consensus in the issue thread to keep it out of the library artifact. Do last; drop if time-constrained.

---

## Suggested execution order for Opus

1. Phase 0 entirely (each item is small, independently testable; write regression tests as you go — P4-2 infrastructure fixes will be needed immediately). Start by diffing `master...origin/w3p` and porting its bug/thread-safety fixes (P1-8 list) so the redesign builds on the most-fixed code.
2. P4-1/P4-2 golden-file harness (safety net before restructuring).
3. Phase 1 redesign, migrating Phase-0-fixed logic into the new core (memory-first write pipeline + FFM read layer per P1-3, w3p unification per P1-8); compat adapter last.
4. Phase 2 format work on top of the new core (v0/v1 polish → sector CRC → attributes → v2–v4 read).
5. Phases 3/5 sweeps, then final P4 coverage pass and CI.

Keep every step green against the existing test suite via the compat adapter; WurstScript is the primary downstream consumer.
