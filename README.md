# GBA Emulator for Android (`io.github.sumankayal247.gbaemulator`)

A native, offline-first Android emulator for **Game Boy Advance (`.gba`), Game Boy Color (`.gbc`), and original Game Boy (`.gb`)** games powered by the pinned **mGBA Libretro core** (`MPL-2.0`).

## Project documents

- [Agent instructions](AGENTS.md) — mandatory engineering and PR workflow rules.
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md) — product scope, architecture, milestones, production gates, and live completion ledger.
- [Architecture Decision Records](docs/adr/) — ADRs `0001`–`0004` covering core pinning, Android platform targets, runtime threading/AV pipeline, and SAF/atomic save storage.
- [Third-party open-source notices](THIRD_PARTY_NOTICES.md) — licence inventory and MPL-2.0 source availability details.
- [Reference screenshots](docs/reference-images/README.md) — the eight supplied UI references.

## Current status

**IN PROGRESS (P0 + P1/P2 vertical slice).**

Implemented in this slice:
- Pinned unmodified `libretro/mgba` submodule (`third_party/mgba` @ `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6`) compiled as `libmgba_libretro.so`.
- Platform-agnostic C++17 Libretro frontend (`gba_core_engine`) with single-owner worker thread, explicit 7-state FSM, bounded shutdown, lock-free SPSC audio ring buffer + stereo resampler, dynamic Core Options v2 store, and untrusted ROM/cheat validation.
- Android `arm64-v8a` native runtime (`libgba_frontend.so`): EGL/OpenGL ES 3.0 `SurfaceView` renderer (aspect-fit & integer scale, nearest-neighbor filtering) + NDK AAudio low-latency output (`minSdk 26`, `targetSdk 36`).
- Jetpack Compose Material 3 app: SAF ROM selection (`OpenDocument`, zero broad storage or network permissions), SHA-256 content-hash ROM identity & relinking, atomic battery SRAM saves with `.bak` corruption recovery + SAF export/import, responsive portrait & landscape virtual handheld with multi-pointer touch tracking, and in-game pause menu.

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

### Build Android debug APK (`arm64-v8a`), unit tests, and lint

Requires JDK 17 and Android SDK (API 36, NDK `27.2.12479018`, CMake `3.22.1`):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Legal & privacy notice

This app does **not** bundle commercial ROMs, copyrighted box art, or proprietary BIOS files. Users select their own legally obtained `.gba`, `.gbc`, or `.gb` files via Android's Storage Access Framework. No `INTERNET` or broad storage permission is requested.
