# ADR 0005: Library Database, Save States, Dynamic Core Options, Cheats, Optional BIOS, and External Controllers (P3–P7)

- **Status:** Proposed (awaiting repository-owner approval)
- **Date:** 2026-11-16
- **Deciders:** Repository owner (`@sumankayal247`)

## Context

ADRs 0001–0004 established the pinned `libretro/mgba` core, Android API 26/36 `arm64-v8a` stack, single-owner native worker thread with EGL/AAudio, and SHA-256 content-hash ROM identity with atomic SRAM saves.

Completing the full MVP (Milestones P3–P7 in `docs/IMPLEMENTATION_PLAN.md`) requires durable local architecture for:
1. **P3 — ROM Library & SAF Directory Scanning:** indexing user-selected SAF folders (`OpenDocumentTree`) alongside single-file imports without duplicating save identity when multiple copies of a ROM exist, preserving user metadata (custom title, favourite, cover art, play time) across rescans, and using explicit Room schema migrations from schema v1.
2. **P4 — Touch Layout Profiles & Editor:** persisting separate portrait and landscape virtual handheld layouts with presets, clamped safe bounds, undo/reset, and haptic feedback controls.
3. **P5 — Save-State Slots, Dynamic Core Options, Cheats, and Optional BIOS:**
   - 8 numbered save-state slots per game completely separate from battery SRAM, with atomic writes, core-revision metadata, checksum validation, `.bak` recovery, and captured frame thumbnails.
   - Dynamic core options queried directly from the pinned `libretro/mgba` core via `RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2` and `RETRO_ENVIRONMENT_SET_CORE_OPTION_DISPLAY`, with persisted global/per-game overrides applied both at ROM load and live during play.
   - Per-game cheat codes strictly validated on both Kotlin and C++ sides prior to `retro_cheat_set()` (mitigating upstream fixed stack buffers and `UNUSED(enabled)`).
   - Optional user-supplied BIOS files (`gba_bios.bin`, `gbc_bios.bin`, `gb_bios.bin`) imported via SAF into app-private `files/system/` with size/hash verification and clear built-in HLE vs custom BIOS status.
4. **P6 — External Bluetooth & USB Controllers:** hot-plug device detection via Android `InputManager`, per-device mapping profiles persisted cleanly (distinguished by hardware descriptor, not display name alone), deterministic arbitration between touch + D-pad + analog sticks, and guaranteed held-input release on disconnect.
5. **P7 — Offline Release Hardening:** privacy policy, release signing guide (zero secrets in Git), and R8/ProGuard release verification in CI.

## Decision

### 1. Room Library Database (`GbaLibraryDatabase`, Schema v1)
- Use **AndroidX Room `2.7.1`** with **KSP `2.1.20-2.0.1`** and exported schema JSON (`app/schemas/`) so every future schema change requires an explicit, unit-tested `Migration` (`fallbackToDestructiveMigration` is explicitly prohibited).
- Separate durable game identity from file locators across five tables:
  - `games`: `rom_id` (PK, `"<system>:<sha256>"`), `sha256`, `system`, `canonical_title`, `custom_title`, `cover_uri`, `is_favorite`, `date_added_ms`, `last_played_ms`, `total_play_time_ms`, `file_size_bytes`, `has_battery_save`.
  - `rom_sources`: `source_uri` (PK), `rom_id` (FK -> `games.rom_id`, CASCADE), `tree_uri`, `display_name`, `last_seen_ms`, `is_available`. Multiple copies of identical ROM bytes attach as multiple rows in `rom_sources` pointing to ONE `games` row and ONE isolated save directory.
  - `saved_folders`: `tree_uri` (PK), `display_name`, `added_ms`, `last_scanned_ms`, `last_scan_game_count`, `last_scan_error`.
  - `cheats`: `id` (PK auto-generate), `rom_id` (FK -> `games.rom_id`, CASCADE), `description`, `code`, `enabled`, `created_ms`.
  - `core_option_overrides`: composite PK (`scope_key`, `option_key`) where `scope_key` is `"global"` or `rom_id`, plus `option_value`.
- SAF folder scanning uses `DocumentsContract.buildChildDocumentsUriUsingTree` directly (bounded recursion depth 4, max 500 ROM files per scan, cancellable coroutine, partial-error reporting without deleting previously indexed games).
- Removing a game from the library deletes only its Room record and staged cache — never the user's original ROM file or battery saves (deleting saves requires a separate explicit confirmation).

### 2. Separate Portrait & Landscape Touch Layout Profiles (P4)
- Persist `TouchLayoutConfig` (separate `portrait` and `landscape` profiles) in DataStore Preferences.
- Each profile stores preset (`Default`, `Compact`, `Large`, `Custom`), `controlScale` (`0.75f..1.30f`), `controlOpacity` (`0.35f..1.0f`), clamped vertical/horizontal offsets for D-pad, action cluster, shoulders, and system buttons (`-40dp..+40dp`), and `hapticsEnabled`.
- Clamping guarantees controls can never be moved off-screen or into unusable positions; the layout editor offers live preview, Reset to Preset, and Discard changes.

#### Proposed amendment: global shell tint (owner approval pending)

The Layout screen may customize a single opaque RGB shell colour for both orientations, with classic purple (`6E00FF`) as the default. Presets and a custom hexadecimal value are presentation controls for that one tint, not skin packs or per-button styles. Gameplay and the editor share palette and control rendering. The editor measures the shared layouts at the current window's configuration dimensions and scales the result for display; orientation-specific system insets can differ, so the rotated preview is an estimate and device verification remains required.

Store the normalized value separately in DataStore under `shell_color_hex_v1`. Existing `portrait_layout_v1` and `landscape_layout_v1` eight-field profiles are not modified or migrated. Missing or corrupt values fall back to classic purple; selecting the Classic preset restores it. Older app versions ignore the new key. Rollback can remove the new key or ignore it without changing profiles, saves or ROMs; Android backup remains disabled under the existing app policy. This amendment requires owner review and Android upgrade/relaunch and both-orientation checks before release.

### 3. Isolated 8-Slot Save States with Thumbnails & Metadata (P5)
- Stored strictly separately from battery SRAM:
  - `files/saves/<system>/<sha256>/states/slot_<1..8>.state` (raw core serialized bytes)
  - `files/saves/<system>/<sha256>/states/slot_<1..8>.state.bak` (previous good snapshot)
  - `files/saves/<system>/<sha256>/states/slot_<1..8>.png` (RGB565->ARGB8888 screenshot thumbnail captured at save time)
  - `files/saves/<system>/<sha256>/states/slot_<1..8>.meta.json` (`schemaVersion=1`, `romId`, `slotIndex`, `coreVersion`, `coreGitRev`, `savedEpochMs`, `byteSize`, `sha256`, `hasThumbnail`)
- Every write uses atomic `tmp + fsync + rename`, rotates `.bak`, verifies SHA-256 on load, quarantines corrupt files without silently deleting them, and warns clearly if core version differs or unserialization fails.
- Overwriting an occupied slot requires explicit user confirmation.

### 4. Dynamic Core Options, Cheats, and Optional BIOS (P5)
- `CoreOptionsStore` tracks `RETRO_ENVIRONMENT_SET_CORE_OPTION_DISPLAY` visibility flags and exports real advertised options via `nativeGetCoreOptionsJson()`.
- Persisted global and per-ROM overrides are passed to `nativeLoadRom` (for boot-time options such as `mgba_use_bios` / `mgba_gb_model`) and applied live via `nativeSetCoreOption`.
- `Tilt Sensor` is **not** exposed in the UI until verified against Android sensor hardware and core sensor callbacks on a real device (per Plan §5.H).
- Cheat codes are validated by `CheatCodeValidator` (Kotlin) AND `CheatValidator` (C++) before `LibretroFrontend::applyCheats()`, which executes `retro_cheat_reset()` and re-applies only enabled codes.
- Optional BIOS files are imported via SAF `OpenDocument` into `files/system/` (`gba_bios.bin` = 16,384 B, `gbc_bios.bin` = 2,304 B, `gb_bios.bin` = 256 B); no proprietary BIOS is bundled.

### 5. External Bluetooth & USB Controllers (P6)
- `ControllerManager` registers an Android `InputManager.InputDeviceListener` to observe connected `SOURCE_GAMEPAD` / `SOURCE_JOYSTICK` hardware without polling or Bluetooth permissions (system Bluetooth settings handles pairing).
- Each device is keyed by `InputDevice.descriptor` (stable hardware descriptor, avoiding name collisions) and mapped via a persisted `ControllerProfile` (defaulting to standard Android gamepad codes: `BUTTON_A`/`B`/`X`/`Y`, `BUTTON_L1`/`R1`, `BUTTON_START`/`SELECT`, `DPAD_*`, `AXIS_HAT_X`/`Y`, `AXIS_X`/`Y` with `0.35f` deadzone).
- `GamepadInputTracker` translates `KeyEvent` and `MotionEvent` per device ID, merges multiple controllers + touch inputs with deterministic opposing-direction sanitization, ignores key repeats, and immediately clears a device's held bitmask on `onInputDeviceRemoved` or lifecycle pause.

## Consequences

- **Positive:** Complete five-tab navigation (`Home`, `Layout`, `Controllers`, `Extras`, `Settings`) with zero stubbed screens or fake options; full per-ROM isolation for SRAM, save states, cheats, and per-game core options; non-destructive Room migration baseline from v1.
- **Negative / Trade-offs:** Adds KSP + Room compiler dependencies (~3s build overhead in CI); real Bluetooth/USB controller hardware testing and Android SAF OEM provider testing remain required on physical devices before any plan milestone can move from `IN PROGRESS` to `DONE`.
