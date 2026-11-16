# Android GBA / GBC Emulator — Implementation Plan

**Status:** P0 + P1/P2 vertical slice IN PROGRESS (awaiting PR review & real-device validation)  
**Target:** Android phones first; tablet support should come from responsive layouts rather than a separate product  
**Systems in scope:** Game Boy Advance (GBA), Game Boy Color (GBC), original Game Boy (GB)  
**Primary experience:** Offline game library + a responsive virtual handheld + native controller support

---

## 1. Product goal

Build a standalone Android app that lets a user select their own legally obtained `.gba`, `.gbc`, and `.gb` game files, organise them into a local library, and play them with either on-screen controls or a connected Bluetooth/USB game controller.

The supplied screenshots are the visual and feature references. They suggest five bottom-level areas — **Home, Layout, Controllers, Extras, Settings** — and a gameplay view that adapts between portrait and landscape. The app should feel like a polished handheld console, but it must remain a usable Android application outside gameplay.

### Product principles

1. **Emulation first.** A real GBA/GB/GBC core must run the game. A mock screen or WebView-only shell is not an emulator.
2. **Offline by default.** Library browsing, gameplay, saves, settings, and controller input must work without an account or network connection.
3. **ROMs remain user-owned.** Do not bundle commercial games, copyrighted box art, proprietary BIOS files, or game assets in the repository or APK.
4. **One source of truth.** The emulator core owns emulation state; repositories own persisted app data; Compose renders state and sends user actions.
5. **Responsive controls.** Portrait and landscape layouts are first-class, with multi-touch and external controllers.
6. **Only ship working options.** Do not show a sensor, filter, cheat format, or core option unless the selected core actually supports it.

## 2. Scope

### In the first release

- GBA, GBC, and original GB game loading.
- Local game library with system grouping, recent games, favourites, search, and last-played metadata.
- Android Storage Access Framework (SAF) file/folder selection; do not request broad storage access.
- Portrait and landscape gameplay layouts inspired by the supplied screenshots.
- Virtual D-pad, A/B, L/R, Start, Select, and an in-game Menu button.
- Multi-touch so movement and action buttons can be held simultaneously.
- Audio/video output, pause/resume, mute, and configurable display scaling.
- Battery-backed game saves (SRAM) and manual save states with multiple slots.
- In-game menu for save/load, restart, quit, fast-forward, orientation lock, and supported core options.
- Cheat list with add/edit/remove flows for formats supported by the core.
- Bluetooth and USB controller discovery, input mapping, and a controller test screen.
- Per-game last-played time and basic metadata.

### Explicitly out of scope for v1

- NES, Nintendo 3DS, DS, or other systems. The reference home screen shows more systems, but this project is specifically GBA/GB/GBC.
- Online accounts, cloud sync, social features, achievements, leaderboards, or telemetry.
- Bundled commercial ROMs, game images, or proprietary firmware.
- Multiplayer/link-cable emulation until the single-player runtime is stable.
- ROM patching, cheat-code downloads, scraping from online game databases, or downloading game files.
- Advanced motion/tilt emulation until the selected core's sensor interface is verified on real hardware.

## 3. Emulator engine decision

### Recommended starting point: mGBA libretro core

Use the **mGBA libretro core** as the first engine candidate. The official Libretro core documentation lists support for `.gba`, `.gbc`, and `.gb`, and identifies the core licence as **MPL-2.0**. This gives one maintained emulation core for all three target systems rather than integrating separate GBA and GB/GBC engines.

Sources:
- [Libretro mGBA core documentation](https://docs.libretro.com/library/mgba/) — supported systems/extensions, BIOS behaviour, core options, and core licence.
- [mGBA FAQ](https://mgba.io/faq.html) — mGBA project licence and its note that the project does not currently provide an official Android/iOS port.
- [Libretro API / RetroArch repository](https://github.com/libretro/RetroArch) — explains the frontend/core split: the frontend supplies video, audio, input, and lifecycle callbacks to a portable core.
- [Libretro licence catalogue](https://docs.libretro.com/development/licenses/) — distinguishes the MIT-licensed Libretro API from the GPL-licensed RetroArch frontend and individual core licences.

**Important engineering implication:** this is not a drop-in Android emulator UI. The app must build the native core for Android and implement its own frontend. Use the Libretro API contract; do not copy or embed the RetroArch application/frontend as a shortcut. Pin the exact upstream core revision and review all bundled dependencies/notices before distribution.

### Core integration shape

- Compile the pinned mGBA libretro core with the Android NDK/CMake for at least `arm64-v8a`. Add `armeabi-v7a` only if it is an intentional device-compatibility target.
- Keep emulation in native C/C++ behind a small JNI boundary.
- Implement the Libretro frontend callbacks needed for environment/options, video, audio, input, logging, and save directories.
- Run `retro_run` on a dedicated emulator worker thread — never on the Compose/UI thread.
- Render video to a dedicated Android `SurfaceView`/OpenGL ES surface. Preserve each system's native aspect ratio and offer pixel-friendly scaling.
- Feed audio through a buffered low-latency Android audio output path. Measure latency and dropouts on real devices before choosing the final implementation.
- Pass Android touch/controller input into a single native input router.
- Keep core options dynamic: read the options exposed by the selected core rather than hard-coding the exact rows shown in the reference image.
- Verify save-state serialization, SRAM persistence, and cheat callbacks against the pinned core before building the UI around them.

### BIOS and firmware

mGBA can use BIOS files when supplied and configured, but the app must not ship proprietary BIOS files. Provide optional per-system BIOS selection in Settings, store only the user's selected file reference, and clearly explain when BIOS is optional versus required by a particular game/core configuration. Basic startup should still be tested without a BIOS.

## 4. Android application architecture

### Recommended stack

| Area | Proposal |
|---|---|
| App language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Single-activity navigation |
| UI state | ViewModels + StateFlow; lifecycle-aware collection |
| Preferences | Preferences DataStore |
| Local library metadata | Room |
| Native runtime | C/C++ via Android NDK + CMake |
| Core interface | Libretro API, wrapped with JNI |
| Video | SurfaceView + OpenGL ES renderer |
| Audio | Dedicated buffered Android audio output; choose the concrete API after latency tests |
| File access | Storage Access Framework (SAF) |
| Tests | Kotlin unit/UI tests + native core/bridge tests + device smoke tests |

Android's current architecture guidance recommends a separated UI/data layer, repositories, unidirectional data flow, ViewModels where useful, and Jetpack Compose for new Android UI. Follow those patterns without introducing unnecessary abstraction.

Source: [Android app architecture recommendations](https://developer.android.com/topic/architecture/recommendations).

### Proposed module/package layout

Start with a small, understandable structure; split Gradle modules only where native build boundaries justify it.

```text
app/
  src/main/java/.../
    navigation/
    ui/
      home/
      gameplay/
      layout/
      controllers/
      extras/
      settings/
    data/
      games/
      saves/
      preferences/
    domain/
      model/
      repository/
    emulator/
      EmulatorSession.kt
      EmulatorController.kt
      NativeEmulatorBridge.kt
  src/main/cpp/
    CMakeLists.txt
    jni/
    libretro/
    video/
    audio/
    input/
  src/test/
  src/androidTest/
docs/
```

### Ownership boundaries

- **GameCatalogRepository:** scans user-selected SAF locations, filters supported extensions, stores URI and metadata, and exposes library data.
- **SaveRepository:** stores per-game battery saves and numbered save-state files; supports safe writes, backups, and export/import.
- **PreferencesRepository:** stores UI, audio/video, control-layout, and controller-mapping preferences.
- **EmulatorSession:** owns one active core instance and its lifecycle; exposes load, run, pause, resume, reset, save, load-state, and close operations.
- **ControllerInputRouter:** converts touch, Android key events, and joystick axes into the core's logical buttons.
- **ViewModels:** expose screen state and user actions; they must not call native core functions directly from composables.
- **Compose UI:** renders state and dispatches actions. It must never execute emulation on the main thread.

## 5. Screens and behaviour

### A. Home — game library

Reference: [08 — Library home](reference-images/08-library-home.jpg)

- Header: app name, selected game directory, choose-directory action, and refresh/rescan action.
- Group cards or rows by **Game Boy Advance**, **Game Boy Color**, and **Game Boy**.
- Game item: title, system label, optional local cover image, last played, and favourite state.
- Provide search, sort, favourites, and a Recently Played area.
- If artwork is unavailable, use a neutral system-specific placeholder. Do not fetch or redistribute copyrighted artwork automatically.
- Empty state: explain how to choose a ROM folder or select individual files.
- Unsupported extensions should be ignored with a clear import message, not crash the scan.
- Tapping a game opens the gameplay screen; long-press/context menu can expose details, remove-from-library, or favourite actions.

**File handling:** use SAF `OpenDocument` / `OpenDocumentTree` and persist URI permissions when needed. Android providers do not always expose normal filesystem paths, so the native bridge must accept ROM bytes or a safely staged app-private file; never assume a `content://` URI is a POSIX path. Keep the original selected URI as the library identity.

### B. Gameplay — virtual handheld

References: [01 — Portrait gameplay](reference-images/01-gameplay-portrait.jpg) and [02 — Landscape gameplay](reference-images/02-gameplay-landscape.jpg)

**Portrait layout**
- Game viewport and bezel in the upper portion.
- L/R shoulder buttons positioned above the main controls.
- D-pad on the lower left; A/B on the lower right.
- Start, Select, Menu, and optional speaker/grip decoration along the bottom.
- The viewport must remain large enough to read, with controls reachable by both thumbs.

**Landscape layout**
- Game viewport centred.
- D-pad on the left; A/B on the right.
- L/R at the outer top corners; Start/Select/Menu placed where they do not overlap the game.
- Use safe insets and adapt to short screens; do not simply rotate and stretch the portrait layout.

**Control behaviour**
- Use pointer IDs / pointer tracking so several buttons can be held at once.
- Avoid a single global pressed boolean; each logical button must track independent pointers and release correctly on cancellation, app pause, or pointer loss.
- Add configurable opacity and size, with a default layout close to the supplied purple handheld concept.
- Keep the game's native aspect ratio (GBA 3:2; GB/GBC 10:9); add integer scaling where possible and letterbox/pillarbox rather than stretching pixels.
- Support immersive gameplay while restoring normal system bars when leaving the session.

### C. Layout

A control-layout editor inspired by the handheld reference:
- Portrait and landscape previews.
- Presets: Classic Handheld and Minimal.
- Adjust virtual button size, opacity, and placement.
- Reset to defaults.
- Save separate portrait and landscape layouts.
- Do not implement arbitrary per-button rotation/skin packs in the first milestone.

### D. Controllers

Reference: [03 — Controllers](reference-images/03-controllers-screen.jpg)

- List currently connected controllers and previously recognised controller profiles.
- Detect devices through Android input APIs; support common Bluetooth HID and USB gamepads.
- Pairing itself should use Android's system Bluetooth settings. The app should guide the user to pair/connect rather than pretending it can pair every controller internally.
- Map D-pad/stick, A/B, L/R, Start, and Select; allow remapping.
- Provide a live test panel showing which logical control is received.
- Remember mappings per controller profile; allow reset to default.
- Handle hot-plug/disconnect without crashing or leaving buttons stuck.
- Prefer physical controller input when connected while keeping touch controls available as an optional overlay.

### E. In-game Menu

Reference: [06 — Game menu](reference-images/06-game-menu.jpg)

Menu actions:
- Save State
- Load State
- Restart
- Quit / Return to Library
- Mute
- Fast-forward
- Lock orientation
- Core Options
- Cheats

Actions must show confirmation where destructive (for example, restart or quit with unsaved data). Mute and fast-forward are toggles with an immediately visible state. Pause the emulation loop while a blocking menu is open unless a deliberate background-audio behaviour is introduced.

### F. Save and Load

Reference: [07 — Save-state slots](reference-images/07-save-state-slots.jpg)

- Start with eight numbered slots to match the reference; show empty/occupied status, timestamp, and optional thumbnail.
- Save-state files must be unique per game and system; never allow one game's state to overwrite another's.
- Keep **save states** separate from **battery-backed game saves (SRAM)**. A save-state is a snapshot of the emulator; SRAM is the game's normal saved progress.
- Flush SRAM safely on pause/exit and after core-reported changes. Use atomic file replacement or a temporary-write/rename strategy to reduce corruption risk.
- Provide import/export or backup of save files before release.

### G. Extras — cheats and utilities

Reference: [05 — Cheat-code dialog](reference-images/05-cheat-code-dialog.jpg)

- Add cheat form with Description, Code, and Cancel/Confirm.
- Support only cheat formats confirmed by the selected mGBA core version; validate input and show useful errors.
- List enabled/disabled cheats, edit, delete, and toggle individual entries.
- Scope cheats to a specific game identity.
- Consider screenshots as a later extra, not a dependency of the MVP.

### H. Settings and Core Options

Reference: [04 — Core-options dialog](reference-images/04-core-options-dialog.jpg)

- Separate app settings (theme, audio volume, default orientation, control visibility) from emulator-core options.
- Display the current core's available option labels, choices, and current values dynamically.
- The screenshot's examples — solar sensor level, LCD ghosting, frameskip, color correction, audio filter and filter level — are references for the type of controls expected, not a promise that every option exists in every core build.
- Apply options at the point required by the core; persist per-game overrides only after testing that the core supports them.
- Optional BIOS selection and a clear firmware status area.
- Avoid exposing non-functional tilt/solar controls. Add sensor input only after confirming the core's sensor API and a real device test.

### I. First-run / game-start experience

Reference: the professor and player-selection screens in [01](reference-images/01-gameplay-portrait.jpg) and [02](reference-images/02-gameplay-landscape.jpg) are examples of **game content rendered inside the emulator viewport**, not app-owned onboarding pages. Do not hard-code Pokémon-specific screens into the app. The emulator should render whichever ROM the user selected.

## 6. Data and file model

### Game record

Minimum fields:
- Stable ID derived from system + canonical selected URI (and, where practical, ROM checksum).
- Display title and detected system.
- File URI / source reference.
- Optional user-edited title and local cover URI.
- Favourite flag, date added, last played, total play time.

### Settings

Preferences DataStore:
- Theme/accent, sound/mute/volume, fast-forward preference, orientation behaviour.
- Touch layout for portrait and landscape.
- Controller mapping profiles.
- Video scaling/filter preferences.
- Optional per-game settings only where a real core capability exists.

### Save storage

- Per-game directory under app-specific storage, separated by system and stable game ID.
- Battery save and save-state slots stored as separate files.
- Never store saves using only a game title; titles can collide.
- Document save format/version and handle invalid/corrupt state files gracefully.

## 7. Delivery milestones

| Milestone | Deliverable | Exit criteria |
|---|---|---|
| **P0 — Foundation** | Android project, build configuration, licence review, pinned mGBA core source/build | **IN PROGRESS** — ADRs 0001–0004, `THIRD_PARTY_NOTICES.md`, pinned `libretro/mgba` submodule, Android Gradle/NDK project, and CI workflow added (awaiting PR review & CI/device verification) |
| **P1 — First playable vertical slice** | Choose one local ROM, boot core, render video, play audio, touch controls | **IN PROGRESS** — native GBA runtime verified on host with synthetic homebrew ROM (video, audio, input, SRAM); Android SAF/EGL/AAudio/Compose handheld implemented (real ARM64 device verification pending) |
| **P2 — GB/GBC coverage** | Enable and verify GB and GBC via the same core | **IN PROGRESS** — native GBC and GB homebrew ROMs verified on host via pinned `libretro/mgba` (real ARM64 device verification pending) |
| **P3 — Library and persistence** | Home library, SAF selection, metadata, battery saves | **IN PROGRESS** — SAF single-ROM open, recent ROMs list with relinking, and atomic SRAM + SAF export/import implemented; folder scanning & Room DB scheduled for P3 PR |
| **P4 — Handheld UX** | Portrait/landscape skins, layout presets, in-game menu | **IN PROGRESS** — portrait/landscape virtual handheld, multi-touch tracker, immersive bars, and core in-game pause menu implemented; layout editor & presets scheduled for P4 PR |
| **P5 — Save states and extras** | Eight save slots, load flow, cheats, dynamic core options | **NOT STARTED** — native serialize/unserialize, `CoreOptionsStore`, and `CheatValidator` implemented & host-tested; Android UI scheduled for P5 PR |
| **P6 — External controllers** | Bluetooth/USB discovery, mapping, hot-plug, test screen | **NOT STARTED** — controller mapping works; disconnects release held inputs; touch controls still work |
| **P7 — Release hardening** | QA, accessibility, performance, privacy, signed release build | **NOT STARTED** — no known data-loss/crash blocker; no broad storage permission; third-party notices included |

### Recommended build order

Do not start by polishing all five tabs. Build the smallest end-to-end vertical slice first:

1. Create the Android app shell and native build.
2. Load one user-selected GBA homebrew ROM.
3. Render frames and output audio.
4. Send touch input to the core.
5. Persist SRAM and prove the same game resumes after relaunch.
6. Add GB/GBC validation.
7. Only then expand the library, menu, save slots, cheats, core options, and controller screens.

This order reduces the main project risk: a beautiful shell without a stable emulator runtime.

## 8. Definition of done for the first public MVP

- [ ] Builds and installs on a real Android ARM64 phone.
- [ ] Imports/opens user-selected `.gba`, `.gbc`, and `.gb` files.
- [ ] Boots verified test ROMs for all three systems.
- [ ] Smooth video and stable audio on the agreed test devices.
- [ ] Touch input supports simultaneous directions and action buttons.
- [ ] Portrait and landscape layouts are usable and do not distort the game.
- [ ] App pause/resume, screen lock, audio interruption, and quit are handled safely.
- [ ] Battery saves survive app restart.
- [ ] Eight save-state slots save and restore the correct game.
- [ ] Bluetooth and USB controller input can be mapped and tested.
- [ ] Cheats and core options shown in the UI are actually supported by the pinned core.
- [ ] No ROMs or proprietary BIOS are bundled.
- [ ] Open-source notices and required MPL source availability are included.
- [ ] No broad storage permission, forced login, or network dependency.

## 9. Risks and decisions to resolve before coding

| Risk / open decision | Proposed treatment |
|---|---|
| No official mGBA Android frontend | Maintain a small native Android Libretro frontend; do not assume desktop UI code is reusable |
| Core build and licence compliance | Pin upstream revision, inventory dependencies, retain notices, publish source for MPL-covered modifications as required |
| SAF URI versus native file path | Use ContentResolver and a byte-buffer/staging bridge; never cast a content URI to a filesystem path |
| Core options vary by build/system | Query the core and render options dynamically |
| Audio latency and frame pacing | Benchmark on target phones before selecting final Android audio API |
| Screen rotation / Surface recreation | Make emulator session lifecycle independent from the Compose screen and safely reattach rendering surfaces |
| Save corruption | Atomic writes, backups, and save-state round-trip tests |
| Touch cancellation / stuck buttons | Track independent pointers and release all held inputs on cancellation/lifecycle changes |
| Sensor emulation | Defer until core support and real hardware mapping are verified |

### Decisions intentionally left open

- Minimum Android API level and final supported device matrix.
- Whether to build only `arm64-v8a` initially or also `armeabi-v7a`.
- Exact mGBA upstream tag/commit and NDK/CMake versions.
- Whether optional shaders are included in v1.
- Whether external save-folder selection is needed in v1 or app-private saves plus export/import are sufficient.
- Final name, package ID, icon, and visual assets.

## 10. Reference design direction

Use the screenshots as a **feature and layout guide**, not as a requirement to clone another app pixel-for-pixel.

- Keep the distinctive purple handheld concept for the gameplay shell.
- Use a restrained dark interface for Home, Layout, Controllers, Extras, and Settings.
- Use a clear blue/cyan accent for active navigation, connected devices, and selected states.
- Keep the pixel-art aesthetic inside the game viewport; app navigation text should remain crisp and readable.
- Preserve the five-tab information architecture but remove unrelated NES/3DS sections from the first release.

**Planning rule:** implementation should begin only after the user reviews this plan and confirms the proposed native Android + mGBA/libretro direction.


---

## 11. Production engineering requirements (mandatory)

The following requirements are release blockers, not optional polish.

### 11.1 Runtime lifecycle and threading

- Model the emulator as an explicit state machine: `Unloaded`, `Loading`, `Running`, `Paused`, `Stopping`, `Stopped`, and `Error`. Define legal transitions and reject invalid commands.
- Exactly one owner controls the native core instance. Serialize all core API calls on the emulator worker thread unless the pinned core/API explicitly documents thread safety.
- UI lifecycle and rendering-surface lifecycle must be independent. Surface recreation, rotation, activity recreation, app backgrounding, and process death must not accidentally create two core instances or leave a native thread running.
- Define a bounded shutdown protocol: stop frame production, release held inputs, flush saves, stop audio, detach/release surface resources, then deinitialize the core. Handle timeout/failure without deadlocking the UI.
- Handle Android audio focus, interruptions, route changes, Bluetooth headset changes, and app background/foreground transitions.
- Define frame pacing, audio buffering, fast-forward throttling, thermal/battery behaviour, and recovery from audio/video device errors. Do not busy-loop while paused.
- Native callbacks must not call Compose/UI code. JNI boundaries must validate handles, lengths, null pointers, buffer sizes, and lifecycle state.

### 11.2 ROM, firmware, and save-file safety

- Treat ROM and firmware files as untrusted input. Validate extension and readable content, enforce configurable size limits, handle truncated/corrupt files, and never trust filenames as identity.
- Do not extract archives automatically in v1. If archive support is added later, defend against path traversal, decompression bombs, duplicate entries, and oversized files.
- Avoid loading an entire large file into Java heap without a measured reason. Define a bounded streaming or app-private staging strategy compatible with SAF providers and the native core.
- ROM identity should use a content hash (SHA-256) plus detected system; URI is a source locator, not a durable identity. Handle duplicate copies of the same ROM and same-title/different-ROM collisions.
- Persist SAF URI permissions and detect revoked/moved/unavailable sources. Show a recoverable missing-file state and let the user relink the ROM without losing its saves.
- Keep SRAM, save states, thumbnails, and metadata isolated by stable ROM identity. Use atomic writes, checksums/version metadata where appropriate, backup/restore, and corruption recovery.
- Never silently overwrite a save-state slot. Show slot occupancy and confirm replacement; make save/load failures visible and actionable.
- Define what happens if the app is force-stopped or killed while a save is being written. Do not claim that Android guarantees a final lifecycle callback.
- Firmware selection must copy or retain a durable app-readable reference, verify expected size/hash only where the upstream core publishes reliable values, and explain that users must supply firmware they are entitled to use.

### 11.3 Library correctness and metadata

- SAF directory enumeration is provider-dependent. Support providers that do not expose filesystem paths and report partial/unreadable scans without losing already indexed games.
- Use Room migrations from the first schema. Test upgrades from every released schema; do not destructively reset user libraries.
- Make scan operations cancellable, incremental, idempotent, and bounded. Avoid duplicate records after repeated scans.
- Separate a game record from a file/source record so duplicate ROM copies can be resolved without duplicating save identity.
- Persist user edits and favourites independently from rescanned metadata.
- Make delete/remove semantics explicit: remove from library must not delete ROM files; save deletion requires a separate explicit confirmation.
- Use WorkManager only for deferrable background indexing/maintenance, not real-time emulation.

### 11.4 Input, accessibility, and layout

- Track each active pointer independently per logical control; correctly handle pointer-up, cancel, focus loss, view detach, and app pause. On every lifecycle interruption, release all pressed inputs.
- Define deterministic conflict handling for D-pad versus analog stick, opposite directions, multiple controllers, and touch + physical input.
- Handle Android key repeat, axis dead zones, analog-to-D-pad thresholds, controller reconnects, duplicate device names, and per-device mapping persistence.
- Provide accessible labels and minimum touch targets for all app UI controls, adequate contrast, scalable text, keyboard/controller navigation where appropriate, and a reduced-motion option.
- Layout editing must support reset/undo or safe discard, avoid off-screen controls, and validate overlapping/unsafe placements.
- Keep the actual game viewport aspect ratio based on core-reported geometry where available; do not hard-code aspect ratios if the core reports a different value for a mode or border.

### 11.5 Security, privacy, and distribution

- Request no broad storage permission. Request only the SAF access needed for selected files/folders.
- No account, analytics, ads, or network access is required for core gameplay. If analytics/crash reporting is ever proposed, document data collected, obtain appropriate consent, and make it opt-in where required.
- Add a privacy policy that accurately describes the app's actual data flows before store publication. Do not claim "no data collected" until SDKs, crash reporting, and network traffic have been audited.
- Keep signing keys, upload keys, API keys, and secrets out of Git. Use GitHub Actions secrets or a local secure signing process.
- Generate an SBOM/dependency inventory for release, pin native source revisions and dependency versions, scan dependencies, and review all licences and notices.
- Include a third-party notices page and comply with MPL-2.0 obligations for modified MPL-covered files and source distribution. Do not assume the whole frontend is automatically MPL-2.0 or GPL; assess each component and dependency separately.
- Do not copy RetroArch frontend code unless its GPL obligations and distribution consequences are deliberately accepted and reviewed. Prefer implementing against the Libretro API.
- Publish only builds for ABIs actually built and tested. Verify 64-bit compliance for Play distribution.
- As of 31 August 2026, Google Play new apps and updates must target Android 16 / API 36 or higher. Set the Play release target accordingly at release time and re-check the policy immediately before submission. [Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878).
- Set a deliberate minimum SDK based on device support and native dependency compatibility; document the rationale and test the oldest supported Android version. Do not silently raise or lower it.

### 11.6 Reliability, observability, and recovery

- Add structured, privacy-safe logs with session IDs and core version; never log ROM contents, save contents, or personal file paths unnecessarily.
- Add crash diagnostics for Kotlin and native crashes, but do not enable a third-party upload SDK without a privacy review and consent decision.
- Provide user-visible error states for unsupported files, missing ROMs, failed core initialization, missing firmware, invalid save states, storage-full conditions, and controller disconnects.
- Test low-storage conditions, permission revocation, process death, app upgrade, corrupted preferences/database, and failed atomic writes.
- Keep a recovery path for saves: backup before migration, preserve the last known good file, and never silently delete corrupt data.
- Define an uninstall/data-loss warning: app-private saves are removed on uninstall unless the user exports/backups them.

## 12. Test strategy and measurable release gates

Tests must be committed with the feature they validate. A feature is not complete because it compiles or looks correct in a screenshot.

### Required test layers

1. **Native unit tests:** input mapping, file/ROM identity, save serialization, state transitions, core callback contracts, option parsing.
2. **Kotlin unit tests:** repositories, Room migrations, URI permission recovery, settings, mapping profiles, validation and error mapping.
3. **Integration tests:** JNI bridge, core lifecycle, video callback, audio callback, input, SRAM, state save/load, cheats, and core options.
4. **Instrumented/device tests:** real Android hardware, rotations, process background/foreground, audio focus, SAF providers, Bluetooth and USB controllers.
5. **End-to-end regression:** install → import/open ROM → play → save → force-stop/relaunch → load → verify progress; repeat for GBA, GBC, and GB.
6. **Fuzz/negative tests:** malformed and truncated ROMs, invalid state files, malformed cheat input, invalid core option values, and JNI boundary misuse.
7. **Performance tests:** frame pacing, dropped frames, audio underruns, input-to-display latency, startup time, memory, CPU, battery and thermal behaviour.
8. **Release tests:** clean checkout reproducible build, signed APK/AAB, ABI inspection, dependency/licence report, privacy/network audit, and upgrade test.

### Required release evidence

- Maintain a device/OS matrix with exact device model, Android version, ABI, test ROM checksum, core revision, and results.
- Test at least one lower/mid-range ARM64 phone and one current Android device. Do not claim universal Android compatibility from a single phone.
- Set numeric performance budgets after measuring the first vertical slice on the agreed reference devices. Record baseline and regression thresholds in `docs/PERFORMANCE_BUDGET.md`; do not invent performance claims before measurement.
- Every bug fix adds a regression test where technically feasible.
- No release-blocking crashes, save corruption, stuck input, audio runaway, or unhandled native errors.
- No P0/P1 security, privacy, licence, or data-loss issues remain open.
- A release candidate must pass the full regression suite on a clean build and upgrade path.

## 13. CI/CD and repository quality

Required before calling the project release-ready:

- Gradle wrapper committed; dependency and plugin versions pinned using version catalog or equivalent.
- Native CMake/NDK versions pinned and documented.
- GitHub Actions for formatting/lint, Kotlin unit tests, native tests/build, Android debug build, dependency checks, and PR validation.
- CI must build from a clean checkout; no local untracked files or developer machine paths may be required.
- Add formatting/lint rules and enforce them in CI. Do not disable a rule globally to hide a local issue.
- Add baseline static analysis for Kotlin and native C/C++ (for example, Android Lint plus an appropriate C/C++ analyzer).
- Add CODEOWNERS or equivalent review ownership for emulator/native architecture, persistence, security, and release configuration.
- Add issue templates and a pull-request template with scope, design/ADR link, tests run, screenshots/video where UI changes, migration/data-loss assessment, licence/security impact, and rollback plan.
- Protect `main`: no direct pushes by agents; merge only reviewed PRs with required checks green.
- Keep generated binaries, ROMs, BIOS files, keystores, signing secrets, local IDE files, and user save files out of Git. Include a tested `.gitignore`.

## 14. Architecture decisions and mandatory pull-request workflow

Use Architecture Decision Records (ADRs) under `docs/adr/` for decisions that affect module boundaries, emulator/core choice, native bridge, threading, storage identity/save format, rendering/audio pipeline, supported Android versions, security/privacy, or distribution/licensing.

**A change is "major architecture" if it changes any of these:**
- Emulator core/API or native build strategy.
- Module boundaries or core ownership/threading model.
- ROM identity, storage layout, save-state/SRAM format, or migration strategy.
- Rendering/audio pipeline or controller/input abstraction.
- Minimum Android version, target SDK strategy, ABI support, or permission/security model.
- Licence/dependency strategy, release/distribution model, or data collection.

For every major architecture change, agents MUST:
1. Stop and write/update the relevant ADR with context, options considered, consequences, migration and rollback plan.
2. Implement the change on a dedicated branch; never commit directly to `main`.
3. Add/update tests and documentation in the same branch.
4. Run the full relevant checks and report exact commands/results. Never report a test as passed if it was not run.
5. Open a **pull request at the end of that architecture task**, with a clear summary, ADR link, trade-offs, test evidence, screenshots/video for UI changes, data migration/compatibility risks, licence/security review, and rollback plan.
6. Leave the PR open for human review. Do not merge, squash, close, or mark the plan item DONE without explicit owner approval.
7. If blocked by missing credentials, hardware, ROM test fixtures, or external access, state the blocker and mark the item BLOCKED; do not fake validation or silently skip it.

Small isolated changes may use a normal PR as repository policy requires. Any feature branch that touches multiple modules or persistent user data should use a PR even if it does not alter the architecture.

## 15. Agent execution contract — no partial completion

All coding agents (including Arena.ai agents) must follow the root `AGENTS.md` and this plan. The following rules are mandatory:

- Read `AGENTS.md`, this plan, relevant ADRs, and existing code before editing.
- Work in small, reviewable vertical slices, but finish each slice to production quality before moving on. "Small slice" does not mean stubbed, mocked, TODO-only, or half implemented.
- Do not create fake buttons, placeholder success states, mock emulator output, hard-coded fake device/controller data, or UI that advertises unsupported core features.
- Do not mark work done because code was written, a screen renders, or the build succeeds. All acceptance criteria and evidence for that item must pass.
- No silent scope reduction. If a requirement cannot be met, explain the technical reason, propose alternatives, and wait for approval when it changes scope or architecture.
- Do not add a second implementation beside an existing one without documenting why and a migration/removal plan.
- Preserve working behaviour and user data. Database/save migrations require tests, backup/rollback strategy, and explicit review.
- Do not suppress compiler warnings, lint, failing tests, or native errors without documenting the root cause and an approved fix.
- No broad refactors, formatting churn, or unrelated dependency upgrades mixed into feature work.
- Never commit ROMs, BIOS files, copyrighted game artwork, save files, credentials, signing keys, or private user data.
- Keep documentation, tests, changelog, and plan status in sync with the implementation.

## 16. Live project status and completion ledger

**Overall implementation status: IN PROGRESS (P0–P7 software implementation & CI verification complete; real ARM64 Android device validation & owner sign-off pending).** All planned milestones P0 through P7 — ADRs `0001`–`0005`, pinned `libretro/mgba` core, C++ Libretro frontend + 9 host/ASan tests, Room library & SAF folder scanner, 8-slot save states, dynamic core options, per-ROM cheats, optional BIOS manager, portrait/landscape layout editor, Bluetooth/USB controller discovery & remapping, 5-tab navigation shell, 11 Kotlin unit test suites, privacy policy, release signing guide, and Debug + R8 Release CI — are implemented in PR #1. Real ARM64 Android hardware testing (device matrix, performance budget measurements, OEM SAF providers, and physical Bluetooth/USB controllers) and repository-owner approval remain required before any item can move to `DONE`.

Use only these status values:
- `NOT STARTED` — no implementation has begun.
- `IN PROGRESS` — implementation exists but acceptance evidence is incomplete.
- `BLOCKED` — cannot proceed; blocker and owner/action are recorded.
- `DONE` — every acceptance criterion is met, tests pass, documentation is updated, and evidence/PR is linked.
- `DEFERRED` — explicitly removed from the current release scope with owner approval and rationale.

| Workstream | Status | Evidence required before DONE |
|---|---|---|
| Repository governance, AGENTS.md, branch protection, PR templates | IN PROGRESS | `.gitignore`, `CODEOWNERS`, issue templates, PR template, and CI workflow committed; branch protection and required checks must still be verified in GitHub settings |
| ADRs and mGBA/libretro feasibility spike | IN PROGRESS | ADRs `0001`–`0005` written; `libretro/mgba` pinned at `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6`; 9/9 host + ASan GBA/GBC/GB tests pass; awaiting owner ADR approval |
| Android app foundation and CI | IN PROGRESS | Gradle 8.13 / AGP 8.10.1 / Kotlin 2.1.20 / KSP / Room 2.7.1 / Compose + NDK project & CI workflow (unit tests, unsuppressed Lint, Debug & R8 Release APKs) passing; awaiting hardware install & upgrade test |
| Native emulator lifecycle/JNI | IN PROGRESS | Single-owner worker thread, 7-state FSM, bounded shutdown, screenshot capture, core-option JSON/overrides, and JNI bridge implemented + 9/9 host & ASan tests passing; real-device lifecycle tests pending |
| Video/audio pipeline | IN PROGRESS | EGL/GLES3 `SurfaceView` renderer + NDK AAudio SPSC resampled pipeline implemented; real-device benchmarks & `docs/PERFORMANCE_BUDGET.md` pending |
| SAF import and library | IN PROGRESS | Room v1 schema (`games`, `rom_sources`, `saved_folders`, `cheats`, `core_option_overrides`), cancellable SAF `OpenDocumentTree` scanner, single-file import, SHA-256 identity, relink, custom covers, search/filter/sort, and unit tests implemented; real-device OEM SAF provider tests pending |
| SRAM persistence and recovery | IN PROGRESS | Atomic temp+fsync+rename writes, `.bak` corruption recovery, and SAF export/import implemented + unit & native round-trip tests; real-device force-stop test pending |
| Touch controls and responsive layouts | IN PROGRESS | Portrait & landscape virtual handhelds, multi-pointer `TouchInputTracker`, clamped layout editor with presets/preview/haptics/Undo/Reset, and unit tests implemented; real-device multi-touch & rotation testing pending |
| Save-state slots | IN PROGRESS | Per-ROM isolated 8-slot save states with captured PNG thumbnails, SHA-256 checksums, `.bak` quarantine/recovery, overwrite confirmation, and native + JVM unit tests implemented; real-device round-trip verification pending |
| Cheats and core options | IN PROGRESS | Dynamic core-options query/UI (global + per-ROM, zero unsupported/hardcoded toggles), validated per-ROM Libretro cheats, optional user BIOS import (`BiosRepository`), and native + JVM unit tests implemented; real-device verification pending |
| Bluetooth/USB controllers | IN PROGRESS | `ControllerManager`, `GamepadInputTracker`, automatic device discovery, per-device remapping & deadzone persistence, live input tester, hot-plug disconnect input release, auto-hide touch controls, and JVM unit tests implemented; physical BT/USB controller matrix testing pending |
| Security, privacy, licence and release compliance | IN PROGRESS | `THIRD_PARTY_NOTICES.md`, `PRIVACY_POLICY.md`, `docs/RELEASE_SIGNING.md`, in-app notices, zero `INTERNET`/broad-storage permissions, and R8 unsigned Release APK CI verification completed; signed release artifact & hardware audit pending |

**Completion rule:** only the project owner may approve a status change to DONE after reviewing the linked evidence/PR. Agents may propose a status update in a PR, but must not self-certify completion. Keep this table current in every feature PR.

## 17. Corrections and core capability constraints

The current Libretro mGBA core documentation states that it supports `.gba`, `.gbc`, and `.gb`; SRAM and save states; Libretro frontend cheat support; and core options. It also states that **native cheats, sensors, camera, and location are not supported** by this core. Therefore:

- The app's cheat UI must implement the Libretro frontend cheat API (for supported code types) and must not claim native mGBA cheat support.
- Do not implement tilt/solar sensor input in v1. The solar sensor *level* option is a core option, but sensor hardware input is listed as unsupported.
- Do not promise LCD ghosting, colour correction, or audio filter options unless the pinned core revision actually exposes them. Query and display only options returned by the running core.
- mGBA core options include (at the time of this plan's review) solar sensor level, opposing directional input, GB model, BIOS use/intro, Super Game Boy borders, idle-loop optimization, and frameskip. The exact list may change with the pinned revision.
- BIOS files are listed as optional in the Libretro mGBA documentation; do not present them as universally required.
- The mGBA project FAQ says there is no official Android/iOS port at present. This plan therefore requires an independently implemented Android frontend and a feasibility spike before committing to the engine for production.

Sources: [mGBA Libretro core capabilities](https://docs.libretro.com/library/mgba/), [mGBA FAQ](https://mgba.io/faq.html).

## 18. Release checklist and project handoff

Before a public release, complete and link evidence for:
- [ ] All MVP functional acceptance criteria.
- [ ] Full automated and device regression suite.
- [ ] Clean install and in-place upgrade tests.
- [ ] Save backup/export and restore tests.
- [ ] App lifecycle, audio focus, orientation, controller and SAF provider tests.
- [ ] Performance budgets met on the declared device matrix.
- [ ] Accessibility and localization review.
- [ ] Privacy/network audit and published privacy policy.
- [ ] Third-party licence inventory, notices, and source-offer/availability obligations.
- [ ] Android target SDK and Play policy re-checked on submission date.
- [ ] Signed release artifact built from a reviewed tag/commit in CI.
- [ ] Release notes, known limitations, support contact, and rollback plan.
- [ ] All plan workstreams DONE or explicitly DEFERRED with owner approval.

This plan is a production-oriented engineering contract, not a guarantee that implementation is automatically production-ready. Production readiness is earned only by passing the evidence-based gates above.
