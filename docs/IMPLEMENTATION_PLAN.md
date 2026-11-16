# Android GBA / GBC Emulator — Implementation Plan

**Status:** Planning baseline  
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
| **P0 — Foundation** | Android project, build configuration, licence review, pinned mGBA core source/build | Debug APK builds; native core builds for target ABI; required notices and source obligations are documented |
| **P1 — First playable vertical slice** | Choose one local ROM, boot core, render video, play audio, touch controls | A known-good homebrew test ROM runs; D-pad + A/B + L/R work; pause/resume does not crash |
| **P2 — GB/GBC coverage** | Enable and verify GB and GBC via the same core | Separate known-good GB, GBC, and GBA homebrew test ROMs boot and render correctly |
| **P3 — Library and persistence** | Home library, SAF selection, metadata, battery saves | User can choose a folder, rescan, launch, close, relaunch, and retain game progress |
| **P4 — Handheld UX** | Portrait/landscape skins, layout presets, in-game menu | Multi-touch works; no stretched display; rotation and system-bar behaviour are stable |
| **P5 — Save states and extras** | Eight save slots, load flow, cheats, dynamic core options | Save/load round-trip works per game; cheat validation and option values match the core |
| **P6 — External controllers** | Bluetooth/USB discovery, mapping, hot-plug, test screen | Controller mapping works; disconnects release held inputs; touch controls still work |
| **P7 — Release hardening** | QA, accessibility, performance, privacy, signed release build | No known data-loss/crash blocker; no broad storage permission; third-party notices included |

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
