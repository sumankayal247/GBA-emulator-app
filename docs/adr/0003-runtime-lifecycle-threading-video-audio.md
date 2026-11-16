# ADR-0003: Runtime Lifecycle, Threading, Video, Audio, and Touch Input Pipeline

- **Status:** Proposed (pending repository-owner PR review)
- **Date:** 2026-11-16
- **Decision owners:** @sumankayal247
- **Related plan sections/issues/PRs:** `docs/IMPLEMENTATION_PLAN.md` §3, §5.B, §11.1, §11.4

## Context

Emulation requires deterministic frame pacing (~59.7275 fps on GBA/GB/GBC), glitch-free audio, responsive multi-touch input, and rock-solid survival across Android lifecycle transitions (screen rotation, `SurfaceView` destruction/recreation, backgrounding, audio focus loss, headphone unplugging, and quit). Calling native Libretro functions from the Android main/Compose thread or allowing two concurrent core instances leads to ANRs, data corruption, or native crashes (mGBA's libretro wrapper holds process-global core pointers).

## Decision drivers

- Single-owner native core serialization — zero concurrent `retro_*` calls and zero calls from Compose/UI threads.
- Complete decoupling between UI/Surface lifecycle and emulator session lifecycle so rotation never resets gameplay.
- Bounded, deadlock-free shutdown protocol that releases inputs, flushes SRAM, stops audio, detaches video, and unloads the core cleanly.
- Crisp pixel-art rendering preserving core-reported aspect ratio (GBA 3:2, GB/GBC 10:9) with optional integer scaling and zero stretching.
- Low-latency buffered audio with clean recovery on Bluetooth/headset route changes and Android audio focus interruptions.
- Multi-pointer touch input with deterministic cancellation and zero stuck buttons.

## Options considered

### Option A — Application-scoped `EmulatorSession` + dedicated native C++ worker thread + EGL/OpenGL ES 3.0 `SurfaceView` + NDK AAudio SPSC ring buffer
- Advantages:
  - Explicit 7-state finite state machine (`Unloaded`, `Loading`, `Running`, `Paused`, `Stopping`, `Stopped`, `Error`) enforced in both native C++ and Kotlin.
  - Dedicated `std::thread` in `LibretroFrontend` serializes every `retro_*` call (`retro_init`, `retro_load_game`, `retro_run`, `retro_reset`, `retro_get_memory_*`, `retro_serialize*`, `retro_unload_game`, `retro_deinit`).
  - `EmulatorSession` lives in the application-scoped container (`GbaEmulatorApp`), surviving Activity configuration changes and `SurfaceView` recreation.
  - `GlVideoRenderer` attaches/detaches `ANativeWindow*` independently using EGL + OpenGL ES 3.0, uploading RGB565 frames (`GL_RGB`, `GL_UNSIGNED_SHORT_5_6_5`) with `GL_NEAREST` sampling and computing aspect-preserving or integer-scaled viewports from core-reported geometry (`retro_game_geometry`).
  - `AAudioOutput` uses built-in Android NDK `<aaudio/AAudio.h>` (`AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`, stereo PCM16) draining a lock-free SPSC `AudioRingBuffer` (~85 ms capacity), automatically rebuilding the stream on `AAUDIO_ERROR_DISCONNECTED` (Bluetooth/headset changes) and filling silence on underrun without blocking the emulation loop.
  - When paused, the emulation worker blocks on a `std::condition_variable` (zero busy-looping, zero battery drain).
- Disadvantages:
  - Requires explicit JNI handle/state validation and careful synchronization between surface attach/detach and EGL rendering.
- Operational/security/licensing impact:
  - Uses only standard Android NDK libraries (`libEGL`, `libGLESv3`, `libandroid`, `libaaudio`, `liblog`); zero external third-party native dependencies.

### Option B — `AudioTrack` via JNI callbacks from native thread + Canvas `Bitmap` rendering
- Advantages:
  - Simpler Kotlin code.
- Disadvantages:
  - Higher audio latency and GC pressure from repeated JNI array copies per frame; slower software scaling compared to OpenGL ES textured quad.

## Decision

Adopt **Option A**:
1. **State machine:** `Unloaded -> Loading -> Running <-> Paused -> Stopping -> Stopped -> Unloaded`, plus `Error` recovery back to `Unloaded`. Invalid state transitions are rejected explicitly and logged safely.
2. **Single-owner native worker thread:** `LibretroFrontend` guards singleton core ownership and executes all Libretro API calls exclusively on its dedicated worker thread via a serialized command queue + condition-variable frame loop. No native callbacks ever invoke Compose/UI code directly.
3. **Bounded shutdown protocol:**
   1. Transition to `Stopping` and wake the worker loop so frame production stops immediately.
   2. Release all held inputs (`InputState::releaseAll()`).
   3. Stop/pause `AAudioOutput` and clear ring buffer.
   4. Extract latest SRAM bytes on the worker thread and hand off to `SramSaveRepository` atomic persistence.
   5. Detach/destroy EGL surface resources safely.
   6. Call `retro_unload_game()`, `retro_deinit()`, and `dlclose()` on the worker thread; join the thread with a bounded timeout guard and report error cleanly if exceeded.
4. **Frame pacing & fast-forward:** Steady-clock pacing matched to core-reported `retro_system_av_info.timing.fps` (default 59.7275 Hz). Fast-forward mode runs at a bounded 2x multiplier (`targetFrameDuration / 2`) while capping audio ring buffer growth so audio never runs away or deadlocks.
5. **Video rendering:** `SurfaceView` hands `Surface` via `ANativeWindow_fromSurface` to `GlVideoRenderer`. Aspect ratio uses core-reported `aspect_ratio` (falling back to `base_width / base_height`), rendered into a centered black letterboxed/pillarboxed viewport with `GL_NEAREST` filtering. Supports `ASPECT_FIT` and `INTEGER_SCALE`.
6. **Audio & Android focus:** `EmulatorSession` requests `AudioManager.AUDIOFOCUS_GAIN` via `AudioFocusRequest` (API 26+), registers `ACTION_AUDIO_BECOMING_NOISY` for headphone/Bluetooth disconnect, mutes/pauses cleanly on focus loss, and handles `AAUDIO_ERROR_DISCONNECTED` inside `AAudioOutput` by reopening the stream off the callback thread.
7. **Multi-touch input:** `TouchInputTracker` tracks every active pointer ID independently across all controls (`D-pad` with 8-way directional sectors + dead zone + thumb slide, `A`, `B`, `L`, `R`, `Start`, `Select`). Opposing directions are sanitized unless explicitly allowed. `releaseAll()` is invoked on pointer cancel, focus loss, view detach, menu open, and Activity `onPause`.

## Consequences

### Positive
- Zero ANRs or UI jank from emulation work.
- Rotation and split-screen resizing preserve live gameplay seamlessly.
- Battery-friendly idle pause (condition variable wait, not spin loop).
- Hardware-accelerated crisp nearest-neighbor pixel rendering.

### Negative / trade-offs
- Device audio latency and frame-pacing jitter still require real ARM64 hardware benchmarking before setting numeric release budgets in `docs/PERFORMANCE_BUDGET.md` (P7).

### Risks and mitigations
- **Audio/video clock drift between 59.7275 Hz GBA clock and 48 kHz AAudio device:** monitor ring-buffer fill level and apply bounded drop/silence guards in P1/P2; measure real device underruns before finalizing dynamic rate control in P4/P7.
- **Surface destroyed mid-frame:** `GlVideoRenderer` guards `ANativeWindow*` lifetime with a dedicated mutex separate from the emulator command queue so surface detach never deadlocks the worker thread.

## Migration and compatibility

Initial baseline. No existing runtime state to migrate.

## Validation evidence

- Native unit tests verify `EmulatorState` transitions, `InputState` multi-source/opposing-direction/releaseAll behavior, `AudioRingBuffer` concurrency/wrap-around/underrun/overrun/mute, and `LibretroFrontend` lifecycle + pause/resume + bounded shutdown against live `libmgba_libretro.so`.
- Kotlin unit tests verify `TouchInputTracker` multi-pointer tracking, cancellation, and `EmulatorState` transitions.
- Real Android device audio/video latency benchmarks: **NOT RUN** (no physical Android device in sandbox; scheduled for owner device testing & P7 `docs/PERFORMANCE_BUDGET.md`).

## Follow-up

- [ ] @sumankayal247 — Review and accept ADR-0003 in PR.
- [ ] Device testing — Measure frame pacing, audio underruns, and thermal behavior on target ARM64 phones and record in `docs/PERFORMANCE_BUDGET.md`.
