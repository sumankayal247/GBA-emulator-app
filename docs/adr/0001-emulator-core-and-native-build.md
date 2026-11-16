# ADR-0001: Emulator Core Selection, Pinning, and Native Build Strategy

- **Status:** Proposed (pending repository-owner PR review)
- **Date:** 2026-11-16
- **Decision owners:** @sumankayal247
- **Related plan sections/issues/PRs:** `docs/IMPLEMENTATION_PLAN.md` §3, §7 (P0–P2), §11.5, §17

## Context

The app must emulate Game Boy Advance (`.gba`), Game Boy Color (`.gbc`), and original Game Boy (`.gb`) games on Android with real native emulation (never Compose/WebView simulation), without bundling commercial ROMs or proprietary BIOS files. mGBA does not ship an official Android port, so we must integrate and build a native emulator core behind our own Android frontend.

We need to decide:
1. Which exact mGBA upstream repository and revision to pin.
2. How to vendor/reference the upstream source in Git without violating MPL-2.0 or cluttering repository history.
3. How to compile and link the core with our Android NDK / CMake frontend while preserving clean ABI and licensing boundaries.
4. Which capabilities of the pinned core revision are verified before any UI exposes them.

## Decision drivers

- Single maintained, accurate core covering GBA, GBC, and GB (`.gba`, `.gbc`, `.gb`) with built-in HLE BIOS fallback when user BIOS is absent.
- Permissive / manageable open-source compliance (MPL-2.0 file-level copyleft vs GPL frontend).
- Reproducible clean-checkout builds for both Android NDK (`arm64-v8a`) and Linux x86_64 host unit/integration testing.
- Strict separation between upstream core source and our independent Android frontend.
- Security hardening against untrusted ROMs, save states, and cheat strings.

## Options considered

### Option A — Pin `libretro/mgba` as a Git submodule (`third_party/mgba`), built unmodified as `libmgba_libretro.so` and dynamically loaded via Libretro C API
- Advantages:
  - Maintained Libretro port of mGBA with CMake `BUILD_LIBRETRO=ON` and Android NDK detection (`ANDROID_NDK_MAJOR`).
  - Exposes verified Core Options v2 definitions including `mgba_gb_model`, `mgba_use_bios`, `mgba_skip_bios`, `mgba_gb_colors`, `mgba_gb_colors_preset`, `mgba_sgb_borders`, `mgba_color_correction`, `mgba_interframe_blending`, `mgba_audio_low_pass_filter`, `mgba_audio_low_pass_range`, `mgba_allow_opposing_directions`, `mgba_solar_sensor_level`, `mgba_force_gbp`, `mgba_idle_optimization`, `mgba_frameskip`, `mgba_frameskip_threshold`, `mgba_frameskip_interval`.
  - Clean MPL-2.0 compliance: upstream files stay completely unmodified in `third_party/mgba`, compiled as an isolated shared library (`libmgba_libretro.so`) exporting only `retro_*` symbols via upstream `link.T`.
  - Exact immutable 40-character commit SHA pin (`7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6`, 2026-09-17).
  - Our frontend compiles against MIT-licensed `libretro.h`, avoiding RetroArch GPL frontend code.
- Disadvantages:
  - Developers/CI must clone with `--recursive` or run `git submodule update --init --recursive`.
  - Dynamic loading via `dlopen` requires verifying all required `retro_*` symbols at load time.
- Operational/security/licensing impact:
  - MPL-2.0 source obligation is trivially satisfied because upstream source is unmodified at the pinned public commit; `THIRD_PARTY_NOTICES.md` and in-app open-source notices document the licence and source URL.

### Option B — Pin `mgba-emu/mgba` tag `0.10.5` directly
- Advantages:
  - Tagged upstream release (`0.10.5`, 2025-03-09).
- Disadvantages:
  - Lacks `mgba_color_correction`, `mgba_interframe_blending` (LCD ghosting), and `mgba_frameskip_threshold` / `mgba_frameskip_interval` in its `src/platform/libretro/libretro_core_options.h` (which are maintained in `libretro/mgba`).

### Option C — Vendor mGBA source files directly into our Git history
- Advantages:
  - Works with plain `git clone` without `--recursive`.
- Disadvantages:
  - Adds >3,100 upstream files (~81 MB) into our repository history and PR diffs.
  - Increases accidental modification risk to MPL-2.0 files.

### Option D — Embed RetroArch frontend code
- Advantages:
  - Existing Android frontend code exists upstream.
- Disadvantages:
  - RetroArch is GPL-3.0 licensed; explicitly rejected by `docs/IMPLEMENTATION_PLAN.md` §3 and §11.5.

## Decision

Choose **Option A**:
1. Pin `https://github.com/libretro/mgba.git` as a Git submodule at `third_party/mgba` locked to commit `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6` (2026-09-17).
2. Configure `third_party/mgba` from our outer `app/src/main/cpp/CMakeLists.txt` with `BUILD_LIBRETRO=ON`, `SKIP_LIBRARY=ON`, optional desktop/extra deps disabled (`BUILD_QT=OFF`, `BUILD_SDL=OFF`, `USE_SQLITE3=OFF`, `USE_ZLIB=OFF`, `USE_PNG=OFF`, `USE_LIBZIP=OFF`, `USE_FFMPEG=OFF`, `USE_ELF=OFF`, `USE_LZMA=OFF`, `USE_EDITLINE=OFF`, `USE_GDB_STUB=OFF`, `USE_DISCORD_RPC=OFF`), and override CMake target property `PREFIX "lib"` externally so Android packages `lib/<abi>/libmgba_libretro.so` without modifying any upstream MPL-2.0 file.
3. Build our platform-agnostic C++17 Libretro frontend (`gba_core_engine`) and Android JNI/EGL/AAudio library (`libgba_frontend.so`), which dynamically loads `libmgba_libretro.so` via `dlopen`/`dlsym`.
4. **Verified capability & safety findings from inspecting pinned `libretro.c`:**
   - Supports GBA, GBC, and GB with built-in HLE BIOS fallback (proprietary BIOS optional).
   - Supports SRAM (`retro_get_memory_data`/`size` with `RETRO_MEMORY_SAVE_RAM`) and save states (`retro_serialize_size`/`retro_serialize`/`retro_unserialize`).
   - Supports Libretro Core Options v2 dynamically (we must always query runtime options rather than hard-coding).
   - **Critical safety finding in upstream `retro_cheat_set`:** upstream ignores the `enabled` parameter (`UNUSED(enabled)`) and copies characters into fixed-size stack buffers (`char realCode[] = "XXXXXXXX XXXXXXXX"` for GBA, `char realCode[] = "XXX-XXX-XXX"` for GB/GBC) without bounds-checking long unbroken tokens. Therefore, our frontend MUST validate and sanitize every cheat string strictly in `CheatValidator` before calling `retro_cheat_set`, and implement cheat toggling by calling `retro_cheat_reset()` followed by `retro_cheat_set()` only for enabled entries.
   - Sensor hardware input (tilt/light sensor hardware) remains excluded from v1 UI per Plan §17.

## Consequences

### Positive
- Clean legal and architectural boundary between MPL-2.0 core and our Android frontend.
- Same C++ core engine (`gba_core_engine`) is compiled and tested natively on Linux x86_64 against real assembled GBA, GBC, and GB homebrew ROMs as well as Android `arm64-v8a`.
- Fast, minimal native binary footprint (no unused desktop libraries compiled in).

### Negative / trade-offs
- Requires `git submodule update --init --recursive` on fresh clone (enforced with an explicit error message in `CMakeLists.txt` and `submodules: recursive` in CI).
- Upgrading the core later requires re-verifying core options, save-state compatibility, and `libretro.c` behaviour via our native integration suite.

### Risks and mitigations
- **Upstream `cmake_minimum_required` deprecation on CMake 4.x host builds:** pass `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` in host test commands (harmless on Android NDK CMake 3.22.1).
- **Save-state incompatibility across core upgrades:** record core name/version metadata alongside save files when save states are added in P5, and never rely on save states as the sole persistence mechanism (SRAM is primary).
- **Untrusted ROM/cheat inputs:** enforce strict validation in `RomValidator` and `CheatValidator` prior to calling into the core.

## Migration and compatibility

Initial baseline — no prior core or user data exists. Future core pin bumps must verify SRAM and save-state compatibility with regression tests before merging.

## Validation evidence

Updated upon build/test execution in the PR (host CMake/GCC native build + real GBA/GBC/GB homebrew ROM tests + CI Android NDK `arm64-v8a` build + APK ABI inspection).

## Follow-up

- [ ] @sumankayal247 — Review and accept ADR-0001 in PR.
- [ ] P5 milestone — Expose dynamic Core Options v2 and Cheat UI backed by verified `CoreOptionsStore` and `CheatValidator`.
