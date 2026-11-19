# GBA Emulator for Android (`io.github.sumankayal247.gbaemulator`)

A native, offline-first Android emulator for **Game Boy Advance (`.gba`), Game Boy Color (`.gbc`), and original Game Boy (`.gb`)** games powered by the pinned **mGBA Libretro core** (`MPL-2.0`).

## Project documents

- [Agent instructions](AGENTS.md) — mandatory engineering and PR workflow rules.
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md) — product scope, architecture, milestones, production gates, and live completion ledger.
- [Architecture Decision Records](docs/adr/) — ADRs `0001`–`0006` covering core pinning, Android platform targets, runtime threading/AV pipeline, SAF/atomic save storage, Room/save-states/cheats/core-options/controllers, and optional broad storage read for device-wide ROM discovery.
- [Privacy Policy](PRIVACY_POLICY.md) — factual description of offline-only local data flows and SAF file access.
- [Release Signing & Distribution Guide](docs/RELEASE_SIGNING.md) — keystore hygiene, R8 release verification, ABI/permission audits, and rollback process.
- [Third-party open-source notices](THIRD_PARTY_NOTICES.md) — licence inventory and MPL-2.0 source availability details.
- [Reference screenshots](docs/reference-images/README.md) — the eight supplied UI references.

## Current status

**IN PROGRESS (P0–P7 feature implementation under review; real ARM64 device verification pending).**

Implemented across milestones P0–P7:
- **Core & Native Engine (P0–P2, P5):** Pinned unmodified `libretro/mgba` submodule (`third_party/mgba` @ `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6`) compiled as `libmgba_libretro.so`; C++17 single-owner worker engine (`gba_core_engine`) with explicit 7-state FSM, bounded shutdown, lock-free SPSC audio resampler, dynamic Core Options v2 JSON + live/boot override bridge, RGB565→ARGB8888 screenshot capture, and untrusted ROM/cheat validation.
- **Android Rendering & Audio (P1–P2):** `arm64-v8a` `libgba_frontend.so` with EGL/OpenGL ES 3.0 `SurfaceView` renderer (aspect-fit & integer scale, nearest-neighbor filtering) + NDK AAudio output (`minSdk 26`, `targetSdk 36`).
- **SAF Library & Room Database (P3):** Non-destructive Room schema (`games`, `rom_sources`, `saved_folders`, `cheats`, `core_option_overrides`), bounded/cancellable SAF `OpenDocumentTree` folder scanner + single-file import, SHA-256 ROM identity, relink without save loss, search/filter/sort, favourites, rename, custom user cover art, and explicit remove-vs-delete-saves actions. The Home tab has a labelled folder picker (with an on-screen “Use this folder” hint), radar **Scan device** / **Scan selected folders only** actions, search, and removable **Recently Played** / **Favorites** shelves.
- **Responsive Controls & Layout Editor (P4):** Portrait and landscape virtual handheld layouts with independent presets (`Default`, `Compact`, `Large`), clamped safe-bounds sliders, live handheld preview, haptics, Undo/Reset, and full in-game pause menu.
- **Save States, Cheats, Core Options & Optional BIOS (P5):** Per-ROM isolated 8-slot save states with captured thumbnails, SHA-256 verification, `.bak` corruption recovery, and overwrite confirmation; dynamic Core Options dialog queried strictly from the running core (global + per-game); validated per-ROM Libretro cheats; optional user-supplied BIOS status & SAF import (`gba_bios.bin`, `gbc_bios.bin`, `gb_bios.bin`) alongside built-in HLE BIOS.
- **External Bluetooth/USB Controllers (P6):** Automatic Android `InputManager` discovery, per-device remapping & analog deadzone persistence, live input tester, combined touch+controller input, auto-hide touch overlay toggle, and immediate button release on hot-plug disconnect.
- **Release Hardening & Compliance (P7):** 5-tab navigation shell (`Home`, `Layout`, `Controllers`, `Extras`, `Settings`), offline privacy policy, release signing guide, in-app uninstall/privacy/MPL-2.0 notices, and CI verification of both Debug and R8-minified Release `arm64-v8a` APKs.

### Library scanning scope

- **Scan device (optional):** tap the radar button once to grant storage read access. On Android 9 and below the app requests `READ_EXTERNAL_STORAGE` with the normal runtime dialog; on Android 10+ it opens the system **All files access** setting for `MANAGE_EXTERNAL_STORAGE`. With access granted, the app performs a bounded scan of shared storage for `.gba`, `.gbc`, and `.gb` files, validates each header/size/SHA-256 identity, skips Android app-private and hidden directories, and adds new sources to the library. It never modifies or deletes files on shared storage. See [ADR-0006](docs/adr/0006-optional-broad-storage-read-for-device-rom-scan.md) for the trade-offs.
- **Scan selected folders only:** works without broad access by re-scanning every folder you granted through Android's folder picker (`ACTION_OPEN_DOCUMENT_TREE`); if none exist it opens the picker. **Import single ROM file** remains available for one-off files.
- **One game per ROM identity:** every scan deduplicates by system + SHA-256 (`<system>:<sha256>`), both within a scan and across repeated scans. Identical copies found at several paths are reported as duplicates and ignored; scan results distinguish unique ROMs, newly added games, and skipped duplicate copies. The library still shows each game once, and repeated scans never create a second entry or reset its metadata/saves.
- If access is declined or revoked, file-discovered entries are marked unavailable until access is restored or you relink them; saves are unaffected. Removing a game from **Recently Played** only clears its play timestamp; removing from **Favorites** only unstars it. Neither action deletes the ROM, library entry, SRAM, or save states.

**Distribution note:** `MANAGE_EXTERNAL_STORAGE` is a restricted Play permission. Store publication requires an **All files access** declaration and owner review; if that use case is not accepted, ship a SAF-only build by removing this permission and the Scan device action. Library and save data stay compatible.

## Building and testing

Clone with submodules initialized:

```bash
git clone --recursive https://github.com/sumankayal247/GBA-emulator-app.git
```

### Run native C++ unit & real mGBA GBA/GBC/GB integration tests (Linux host)

```bash
cmake -S app/src/main/cpp -B build-host -G Ninja \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DGBA_BUILD_HOST_TESTS=ON
cmake --build build-host --parallel
ctest --test-dir build-host --output-on-failure --verbose
```

### Build Android debug & unsigned R8 release APKs (`arm64-v8a`), unit tests, and lint

Requires JDK 17 and Android SDK (API 36, NDK `27.2.12479018`, CMake `3.22.1`):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## Legal & privacy notice

This app does **not** bundle commercial ROMs, copyrighted box art, or proprietary BIOS files. Users select their own legally obtained `.gba`, `.gbc`, or `.gb` files via Android's Storage Access Framework or, after explicitly granting optional storage read access, discover them on device storage. No `INTERNET` or write permission is requested; see [PRIVACY_POLICY.md](PRIVACY_POLICY.md) and [ADR-0006](docs/adr/0006-optional-broad-storage-read-for-device-rom-scan.md).
