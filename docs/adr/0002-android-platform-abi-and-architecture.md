# ADR-0002: Android Platform Targets, Package Identity, ABI Support, and App Architecture

- **Status:** Proposed (pending repository-owner PR review)
- **Date:** 2026-11-16
- **Decision owners:** @sumankayal247
- **Related plan sections/issues/PRs:** `docs/IMPLEMENTATION_PLAN.md` §4, §9, §11.5, §13

## Context

`docs/IMPLEMENTATION_PLAN.md` §9 intentionally left open the app identity, minimum Android API level, target ABI matrix, and module layout prior to implementation. On 2026-11-16, the repository owner confirmed the baseline choices before starting the P0 + P1/P2 vertical slice.

## Decision drivers

- Google Play policy (effective 31 August 2026) requires new apps and updates to target Android 16 (API 36) or higher.
- Low-latency native audio (Android NDK AAudio) and mature Storage Access Framework (SAF) support are available natively starting at Android 8.0 (API 26).
- Minimize initial native build/test matrix complexity while targeting modern 64-bit Android hardware (`arm64-v8a`), matching Play 64-bit requirements.
- Keep project structure clean, understandable, and easy to review without premature multi-module fragmentation.

## Options considered

### Option A — Single Android app module (`app/`), `minSdk 26` / `compileSdk 36` / `targetSdk 36`, `arm64-v8a` only initially
- Advantages:
  - Direct NDK AAudio (`<aaudio/AAudio.h>`) support with zero external C++ audio library dependencies.
  - Meets Google Play 2026 target SDK (API 36) and 64-bit ABI compliance out of the box.
  - Faster local and CI NDK builds and smaller APK size.
  - Clean package separation inside `app/` (`domain/`, `data/`, `emulator/`, `ui/`) without unnecessary Gradle subproject overhead.
- Disadvantages:
  - Devices running Android 7.x (API 24–25) or 32-bit-only `armeabi-v7a` devices are not supported in this baseline.
- Operational/security/licensing impact:
  - Zero broad storage permissions (`READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` are never requested).
  - Zero network permissions (`INTERNET` is omitted from `AndroidManifest.xml`), enforcing offline-by-default privacy at the OS sandbox level.

### Option B — `minSdk 24` + `armeabi-v7a` + `arm64-v8a`
- Advantages:
  - Broader compatibility with older 2016–2018 budget Android phones.
- Disadvantages:
  - Requires OpenSL ES or AudioTrack fallback for API 24–25 and doubles native build/test surface without dedicated 32-bit test hardware.

## Decision

Adopt **Option A** as confirmed by the owner:
- **App display name:** `GBA Emulator`
- **Android Application / Package ID:** `io.github.sumankayal247.gbaemulator`
- **SDK levels:** `minSdk = 26` (Android 8.0), `compileSdk = 36`, `targetSdk = 36` (Android 16)
- **Native ABI target:** `arm64-v8a` only in the initial baseline (CI verifies only `arm64-v8a` `.so` libraries are packaged). Adding `armeabi-v7a` later requires a dedicated ADR update and 32-bit device testing.
- **Pinned toolchain:**
  - Gradle Wrapper `8.13`
  - Android Gradle Plugin `8.10.1`
  - Kotlin `2.1.20`
  - Jetpack Compose BOM `2025.05.00` + Material 3
  - Android NDK `27.2.12479018` + CMake `3.22.1`
  - All versions pinned centrally in `gradle/libs.versions.toml`.
- **Architecture:** Single-activity Jetpack Compose app with clear unidirectional data flow (`UI -> ViewModel -> EmulatorSession / Repositories -> Native JNI / Storage`). Zero `INTERNET` or broad storage permissions in `AndroidManifest.xml`.
- **UI honesty constraint:** Only screens and actions backed by working functionality are surfaced in the P0–P2 vertical slice (Home ROM picker & recent ROMs, Gameplay virtual handheld in portrait & landscape, In-Game Pause Menu with working toggles/actions, Settings & Third-Party Notices). Full library scanning (P3), layout editor (P4), 8 save-state slots (P5), cheats/core options UI (P5), and Bluetooth/USB controllers (P6) will be added in subsequent slices as their backing features are verified.

## Consequences

### Positive
- Offline-only guarantee enforced directly by Android manifest (no `INTERNET` permission).
- Clean, reproducible Gradle + NDK build with minimal configuration surface.
- Full API 26+ AAudio & AudioFocusRequest capabilities available without compatibility shims.

### Negative / trade-offs
- Excludes Android < 8.0 and 32-bit-only ARMv7 devices unless revisited in a future ADR.

### Risks and mitigations
- **Play policy changes before public release:** re-verify target SDK requirements in P7 release checklist.
- **Device matrix coverage:** before marking release-ready, verify on at least one mid-range ARM64 Android 8–12 phone and one current Android 15/16 device.

## Migration and compatibility

Initial baseline. Package ID `io.github.sumankayal247.gbaemulator` is permanent once published; changing it later creates a separate Play Store app identity.

## Validation evidence

Verified via GitHub Actions CI (`./gradlew lintDebug testDebugUnitTest assembleDebug`) and APK ABI/manifest checks. Local sandbox lacks JDK/Android SDK; all Android checks are executed in CI and reported transparently.

## Follow-up

- [ ] @sumankayal247 — Review and accept ADR-0002 in PR.
- [ ] P7 milestone — Test oldest supported Android version (API 26) and current Android 16 device in hardware matrix.
