# Release Signing & Distribution Guide

This document defines the release build, signing, verification, and rollback process for **GBA Emulator** (`io.github.sumankayal247.gbaemulator`).

## 1. Keystore & Secret Hygiene

- **NEVER** commit `.jks`, `.keystore`, `keystore.properties`, Play upload keys, API tokens, or signing passwords into Git (protected by `.gitignore`).
- Store the upload keystore outside the repository or in encrypted GitHub Actions repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) when configuring signed release workflows.

## 2. Target Platform & ABI Policy

- **Minimum SDK:** API 26 (Android 8.0 Oreo — required for NDK AAudio).
- **Target / Compile SDK:** API 36 (Android 16 — satisfies Google Play's August 31, 2026 API 36 requirement; always re-verify Play policy immediately before store submission).
- **ABI:** `arm64-v8a` only (64-bit compliant; never package untested ABIs).
- **R8 / ProGuard:** Enabled for `release` builds (`isMinifyEnabled = true`, `isShrinkResources = true`) with explicit keep rules in `app/proguard-rules.pro` for JNI native methods, Room entities/DAOs, and AndroidX lifecycle classes.

## 3. Local Unsigned Release Verification

To verify the R8-minified release APK locally from a clean checkout:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The unsigned release APK is emitted to:
`app/build/outputs/apk/release/app-release-unsigned.apk`

## 4. Signing a Release Artifact Locally

Sign and zipalign an approved release tag/commit using Android SDK `build-tools/36.0.0` (or Gradle `signingConfigs` injected via environment variables):

```bash
$ANDROID_HOME/build-tools/36.0.0/zipalign -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app-release-aligned.apk

$ANDROID_HOME/build-tools/36.0.0/apksigner sign \
  --ks /path/to/secure/upload-keystore.jks \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --out app-release-signed.apk \
  app-release-aligned.apk

$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose app-release-signed.apk
```

For Google Play distribution, build an Android App Bundle (`./gradlew bundleRelease`) and sign via Play App Signing.

## 5. Mandatory Pre-Release Verification Checklist

Before publishing any release build:
1. Verify clean-checkout CI pass: native host + ASan/UBSan tests, Kotlin unit tests, unsuppressed Android Lint, debug APK, and unsigned R8 release APK checks.
2. Verify APK contents via `unzip -l` and `aapt2 dump permissions`:
   - Contains `lib/arm64-v8a/libmgba_libretro.so` and `lib/arm64-v8a/libgba_frontend.so`.
   - Contains **no** `armeabi-v7a`, `x86`, or `x86_64` libraries.
   - Contains **no** `.gba`, `.gbc`, `.gb`, or BIOS files.
   - Declares **no** `INTERNET`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions.
3. Run the real-device regression matrix (clean install + in-place upgrade, GBA/GBC/GB ROM launch, SRAM save & force-stop reload, 8-slot save states, Bluetooth & USB controllers, rotation/backgrounding, SAF provider revocation & relink) and record results in `docs/PERFORMANCE_BUDGET.md`.
4. Confirm `THIRD_PARTY_NOTICES.md` and `PRIVACY_POLICY.md` match the shipped commit and pinned `third_party/mgba` revision (`7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6`).

## 6. Rollback Plan

- Every release tag corresponds to a single Git commit and exported Room schema JSON under `app/schemas/`.
- Because Room migrations are strictly non-destructive and SRAM/save-state files preserve `.bak` backups on disk, rolling back an application binary release on Play Console (staged rollout halt -> previous version code) preserves user saves without schema destruction as long as no irreversible schema bump was deployed without downgrade compatibility.
