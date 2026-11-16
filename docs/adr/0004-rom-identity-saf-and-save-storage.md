# ADR-0004: Untrusted ROM Identity, SAF Staging, and Atomic Save Storage

- **Status:** Proposed (pending repository-owner PR review)
- **Date:** 2026-11-16
- **Decision owners:** @sumankayal247
- **Related plan sections/issues/PRs:** `docs/IMPLEMENTATION_PLAN.md` §5.A, §5.F, §6, §9, §11.2, §11.5, §11.6

## Context

Android apps must access user-owned ROM files via the Storage Access Framework (SAF) without requesting broad storage access (`READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`). SAF `content://` URIs are provider-backed streams, not guaranteed POSIX filesystem paths, and permissions can be revoked or files moved externally. Furthermore, ROM files and imported saves are untrusted user input, and battery-backed SRAM saves represent irreplaceable user game progress that must survive process death, crashes, and ROM relinking.

On 2026-11-16, the repository owner confirmed **app-private saves + SAF export/import** as the v1 save storage strategy.

## Decision drivers

- Zero broad storage permissions; strict SAF `OpenDocument` / `CreateDocument` access only.
- Treat all ROMs and imported save files as untrusted data with explicit validation and bounded streaming.
- Stable, collision-proof ROM identity independent of display filename or mutable `content://` URI.
- Corruption-resistant SRAM persistence with backup recovery and user-controlled SAF backup/restore.
- Clear user notice that app-private saves are removed by Android on app uninstall unless exported.

## Options considered

### Option A — Content-hash ROM identity (`<system>_<sha256>`) + bounded streaming SAF staging + atomic app-private SRAM with `.bak` recovery and SAF Export/Import
- Advantages:
  - Works identically across all Android SAF providers (local storage, SD cards, cloud providers) without assuming POSIX paths.
  - Never loads an arbitrary unbounded file into Java heap: streams in 64 KiB chunks while validating max size limits and computing SHA-256 simultaneously.
  - Duplicate copies of the same ROM share one stable save identity; different ROMs with the same filename (`game.gba`) never overwrite each other's saves.
  - If a SAF URI permission is revoked or the file moves, the user can relink the ROM without losing its SRAM save (matched by SHA-256 identity).
  - Atomic SRAM write (`battery.sav.tmp` -> write -> `fd.sync()` -> copy existing valid `battery.sav` to `battery.sav.bak` -> atomic rename) + SHA-256 checksum metadata (`meta.json`) prevents half-written corrupt saves and enables automatic recovery from `.bak`.
  - User can export/import `.sav` backups anytime via SAF `CreateDocument` / `OpenDocument`.
- Disadvantages:
  - Staging a copy into `cacheDir/staged_roms/` uses temporary cache space up to the ROM size (max 32 MiB for GBA, 8 MiB for GB/GBC).
  - App-private saves are deleted if the user uninstalls the app (mitigated by explicit Settings notice + easy SAF Export).
- Operational/security/licensing impact:
  - Strict extension allowlist (`.gba`, `.gbc`, `.gb`), no archive extraction in v1 (eliminates zip-slip / decompression bomb surface), explicit header sanity & size bounds (GBA: 192 B..32 MiB; GB/GBC: 32 KiB..8 MiB; SRAM import: 1 B..256 KiB).

### Option B — Pass raw SAF file descriptor directly to mGBA VFS / store saves inside a SAF DocumentTree
- Advantages:
  - No cache staging copy; saves survive uninstall automatically if stored in external folder.
- Disadvantages:
  - Libretro `retro_load_game` expects either a filesystem path or contiguous memory buffer; SAF providers vary widely on seekability and atomic rename guarantees (many SAF DocumentProviders do not support atomic rename or `fsync`, increasing save corruption risk).

## Decision

Adopt **Option A**:
1. **Untrusted ROM validation (`RomIngestionValidator` + native `RomValidator`):**
   - Allow only `.gba`, `.gbc`, `.gb` extensions (case-insensitive); reject archives (`.zip`, `.7z`, etc.) with a clear error message in v1.
   - Stream from `ContentResolver.openInputStream(uri)` in bounded 64 KiB chunks to a temp file under `cacheDir/staged_roms/`, aborting immediately if bytes exceed system cap (32 MiB for GBA; 8 MiB for GB/GBC) or fall below minimum header size (192 B for GBA; 336 B / 0x150 B for GB/GBC).
   - Inspect header bytes to verify system authenticity (GBA branch opcode + `0x96` fixed byte at `0xB2`; GB/GBC header magic + CGB flag at `0x143`) and detect actual system (`GBA`, `GBC`, `GB`).
   - Compute `SHA-256` during streaming and derive durable identity `romId = "${system.id}_${sha256}"`.
   - Persist SAF URI read permission via `takePersistableUriPermission` when granted; store URI separately as `sourceUri` (never as primary save key).
2. **SRAM Save Storage (`SramSaveRepository`):**
   - Directory layout: `filesDir/saves/<system>/<sha256>/`
     - `battery.sav` — active battery-backed SRAM
     - `battery.sav.bak` — last-known-good backup rotated before every overwrite
     - `meta.json` — format version (`1`), ROM ID, SRAM byte length, SHA-256 checksum, updated epoch ms
   - Write protocol:
     1. Reject empty or oversized (> 256 KiB) SRAM payloads.
     2. Write to `battery.sav.tmp`, flush and `FileDescriptor.sync()`.
     3. If a valid `battery.sav` exists, copy/sync it to `battery.sav.bak`.
     4. Atomically move `battery.sav.tmp` -> `battery.sav` (`StandardCopyOption.REPLACE_EXISTING` + `ATOMIC_MOVE` with same-directory fallback).
     5. Write `meta.json.tmp` -> sync -> atomic move -> `meta.json`.
   - Read & corruption recovery protocol:
     1. Read `battery.sav` and verify against `meta.json` SHA-256 (if metadata exists).
     2. If `battery.sav` is missing or checksum mismatches, preserve the corrupt file as `battery.sav.corrupt.<timestamp>` (never silently discard!), fall back to `battery.sav.bak`, and surface a visible recovery notice to the user.
   - Flush cadence: flush SRAM on Activity `onPause`/`onStop`, on explicit Save SRAM action, on Quit/Restart, and periodically when SRAM hash changes during gameplay (mitigating sudden process kill where Android does not guarantee final callbacks).
   - Export/Import: SAF `CreateDocument` (`application/octet-stream`) to export a copy of `battery.sav`, and SAF `OpenDocument` to import a validated `.sav` file (backing up the existing save to `.bak` before replacing).
3. **Uninstall warning:** Display a clear notice in Settings that app-private saves are removed when the app is uninstalled unless exported via SRAM Backup.

## Consequences

### Positive
- High durability against crashes, power loss, and mid-write process kills.
- Safe against filename collisions, SAF URI revocation, and untrusted/corrupt files.
- Clean separation between SRAM (battery saves) and future P5 save states (`states/slot_1.state` .. `slot_8.state` under the same per-ROM directory).

### Negative / trade-offs
- Staged ROM copies occupy app cache space (bounded by LRU cleanup when cache grows).
- Users must export saves manually before uninstalling until optional backup automation is evaluated post-v1.

### Risks and mitigations
- **Storage-full errors during staging or SRAM write:** catch `IOException`, clean up `.tmp` files without touching existing valid `battery.sav`, and surface an actionable storage error to the user.
- **Process death mid-gameplay:** dirty-check periodic SRAM flush every 15 seconds while running reduces maximum unsaved progress window even on SIGKILL.

## Migration and compatibility

Schema version `meta.json` starts at `version = 1`. Future format changes must preserve read compatibility with `version = 1`, create a backup before migrating, and include automated migration tests.

## Validation evidence

- Kotlin unit tests (`RomIngestionValidatorTest`, `SramSaveRepositoryTest`) covering valid/invalid/truncated/oversized ROMs, SHA-256 identity stability, filename collision isolation, atomic write + `.bak` creation, primary corruption recovery to `.bak`, and export/import round-trip.
- Native integration tests verifying mGBA SRAM persistence round-trip across core unload/reload for GBA, GBC, and GB homebrew test ROMs.

## Follow-up

- [ ] @sumankayal247 — Review and accept ADR-0004 in PR.
- [ ] P3 milestone — Migrate recent-game metadata to versioned Room database with explicit migration tests.
- [ ] P5 milestone — Add numbered save-state slots (`states/slot_1..8.state`) under the same `<system>/<sha256>/` directory isolated from `battery.sav`.
