# ADR-0006: Optional Broad Storage Read for Device-wide ROM Discovery

- **Status:** Proposed (pending repository-owner review and Google Play policy decision)
- **Date:** 2026-11-19
- **Decision owners:** @sumankayal247
- **Related plan sections/issues/PRs:** `docs/IMPLEMENTATION_PLAN.md` §5.A, §11.2, §11.5; PR #6; user request 2026-11-19

## Context

The library currently indexes ROMs only from folders the user grants through SAF `ACTION_OPEN_DOCUMENT_TREE` (ADR-0004/0005). The user now wants the app to **ask for storage permission and scan the phone for games** with one action.

Android does not offer a general runtime "read all files" permission:

- API 26–29: `READ_EXTERNAL_STORAGE` grants read access to shared external storage.
- API 30+ (including the app's API 36 target): `READ_EXTERNAL_STORAGE` no longer enables reliable access to arbitrary non-media files. The only app-facing mechanism for broad non-media access is the special **All files access** permission (`MANAGE_EXTERNAL_STORAGE`), granted in system Settings rather than a runtime dialog. `READ_MEDIA_*` permissions cover media only and cannot expose `.gba`/`.gbc`/`.gb` files via `MediaStore`.

Therefore a literal device-wide ROM scan requires a permission-model change. The library, save identity, and ROM validation rules from ADR-0004/0005 must remain intact.

## Decision drivers

- One-tap discovery of supported ROMs already on the device, including files outside user-selected SAF folders.
- No change to SHA-256 ROM identity, app-private SRAM/save-state isolation, or existing Room schema.
- No network access, uploads, or third-party SDKs; user files remain on-device.
- Explicit user consent with a clear explanation and a working SAF fallback if consent is declined.
- Bounded, cancellable, non-destructive scanning of untrusted files.
- Honest Google Play distribution assessment before release.

## Options considered

### Option A — Keep SAF-only discovery
- Advantages: no broad permission, simplest Play posture, already implemented.
- Disadvantages: cannot satisfy the device-wide scan request; users must know and select every folder.
- Rejected as the primary action for this request; retained as a first-class fallback.

### Option B — `READ_MEDIA_*` + `MediaStore` query on API 33+
- Advantages: runtime permission dialog.
- Disadvantages: ROMs are not media files; on API 29+ `MediaStore.Files` does not reliably expose arbitrary non-media files. Would appear to work but silently miss most games — explicitly rejected (no fake success states).

### Option C — Hybrid legacy runtime permission + opt-in All files access (chosen)
- API 26–29: request `READ_EXTERNAL_STORAGE` through the normal runtime permission dialog.
- API 30+: explain the need, then open `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`; scan only after `Environment.isExternalStorageManager()` returns true.
- If access is declined or unavailable, keep SAF folder scanning and single-file import fully functional.
- Scan files via `java.io.File`, bounded by depth/file count, skipping Android app-private directories and hidden directories, with symlink-loop protection.
- Reuse `RomIngestionValidator` extension/header/size/SHA-256 checks; store `file://` absolute paths as additional `rom_sources` locators. ROM identity, SRAM, and save states stay unchanged.
- Every scan deduplicates by `<system>:<sha256>` both within the scan and across repeated scans, so identical copies are ignored and reported as duplicates rather than creating another game entry or inflating "new" counts.
- Advantages: actually finds ROMs across shared storage on supported devices; no schema migration; reversible.
- Disadvantages / risks:
  - `MANAGE_EXTERNAL_STORAGE` is a restricted permission. Google Play requires an **All files access** declaration and a qualifying core use case; an emulator may not qualify. Release distribution may require a SAF-only build or a policy-approved declaration. Owner decision required before store submission.
  - Users must leave the app once to grant the special permission; some OEMs may hide or restrict the setting.
  - Broad read access increases blast radius if a future bug mishandles files; mitigated by read-only access, no network, bounded traversal, validation of every candidate, and no writes outside app-private cache.
  - Access can be revoked later; cached staged ROMs must not be trusted silently, and affected sources must be marked unavailable with a relink recovery path.

## Decision

Implement Option C as an **opt-in** feature. The Home library exposes a radar **Scan device** action that requests the applicable permission (or opens the system settings screen on API 30+), then runs a bounded scan of shared-storage roots. SAF folder scanning remains available under **Scan selected folders only**, and single-file import remains available.

Only `.gba`, `.gbc`, and `.gb` files that pass the existing header/size validation are indexed. File paths are stored as `file://` URIs in `rom_sources` alongside existing SAF `content://` sources; one game identity still maps to one save directory. No schema version change, no new dependency, and no network permission.

## Consequences

### Positive
- One action can discover games anywhere in shared storage the user has allowed the app to read.
- Existing libraries, favourites, saves, cheats, and core options continue to work unchanged.
- Permission denial degrades to the existing SAF workflow instead of breaking the app.

### Negative / trade-offs
- A restricted Play permission is declared; store eligibility needs owner review and a Play Console declaration.
- The permission prompt/settings round-trip is unavoidable on API 30+.
- A device scan is bounded (depth and file-count limits), so extremely deep/very large collections may need SAF folders or multiple scans.

## Migration, compatibility, and rollback

- No Room schema version bump, no data migration, and no save-format change. Existing installs keep their database and saves on upgrade.
- Newly discovered file sources are additive rows in `rom_sources`; duplicate ROM bytes are deduplicated by the existing `<system>:<sha256>` game identity.
- Revoked access marks affected `file://` sources unavailable; saves remain under the stable ROM ID and relinking does not lose them.
- Rollback: revert the permission manifest entries, UI entry points, and file-path staging support. Existing file-sourced records become unavailable until relinked via SAF; no destructive cleanup is needed.
- Release rollback if Play rejects the declaration: ship a SAF-only variant by removing `MANAGE_EXTERNAL_STORAGE` and hiding the device-scan action; library/save data remain compatible.

## Security and privacy review

- No `INTERNET`, `WRITE_EXTERNAL_STORAGE`, analytics, ads, or crash-upload SDKs.
- ROM files are untrusted: extension allowlist, bounded streaming, size caps, header validation, SHA-256 identity, no archive extraction, no automatic execution of file contents.
- Scan is read-only outside the app cache; staged copies use the existing app-private cache and the existing 8-file pruning.
- Privacy policy and README must describe the new optional permission before release; CI permission audit must allow only the two explicitly declared read permissions.
