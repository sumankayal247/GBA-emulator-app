# Third-Party Open-Source Notices

This application integrates open-source software components listed below. No commercial ROMs, copyrighted game artwork, or proprietary BIOS files are bundled with this application.

---

## 1. mGBA (Libretro Core)

- **Component:** `libmgba_libretro.so` (built from `third_party/mgba`)
- **Upstream repository:** https://github.com/libretro/mgba (upstream project: https://github.com/mgba-emu/mgba)
- **Pinned commit SHA:** `7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6` (2026-09-17)
- **Licence:** Mozilla Public License Version 2.0 (MPL-2.0)
- **Modifications:** None. Upstream source files in `third_party/mgba` are compiled unmodified as a separate shared library (`libmgba_libretro.so`) loaded dynamically via the Libretro C API.
- **Source availability (MPL-2.0 §3.2):** Complete corresponding source code for the pinned revision is publicly available at the pinned upstream repository commit above and referenced directly by this repository's `third_party/mgba` submodule. Any future modifications to MPL-2.0 covered files must be published in source form under MPL-2.0.
- **Full licence text:** https://www.mozilla.org/en-US/MPL/2.0/ (and `third_party/mgba/LICENSE`).

---

## 2. Libretro API Header (`libretro.h`)

- **Component:** Libretro C API specification header (`src/platform/libretro/libretro.h` included with `third_party/mgba`)
- **Upstream repository:** https://github.com/libretro/libretro-common
- **Licence:** MIT License
- **Note:** This project implements an independent Android frontend against the MIT-licensed Libretro C API contract and does not embed or copy GPL-licensed RetroArch frontend code.

---

## 3. Android Jetpack, Kotlin, Room, KSP, and Coroutines Libraries

All AndroidX, Jetpack Compose, Material 3, Lifecycle, Room (`2.7.1`), DataStore (`1.1.7`), Activity, Core KTX, Kotlin Standard Library (`2.1.20`), Kotlin Symbol Processing (`2.1.20-2.0.1`), and Kotlinx Coroutines (`1.10.2`) artifacts pinned in `gradle/libs.versions.toml` are licensed under the **Apache License, Version 2.0**:
- https://www.apache.org/licenses/LICENSE-2.0

---

## 4. Test Dependencies (Not Shipped in Release APK)

- **JUnit 4:** Eclipse Public License 1.0 (test classpath only)
- **Kotlinx Coroutines Test:** Apache License 2.0 (test classpath only)
- **AndroidX Room Testing:** Apache License 2.0 (test classpath only)
