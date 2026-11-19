# Privacy Policy — GBA Emulator (`io.github.sumankayal247.gbaemulator`)

**Effective Date:** 2026-11-16

This Privacy Policy accurately describes the actual data flows of the **GBA Emulator** Android application (`io.github.sumankayal247.gbaemulator`).

## 1. Summary

- **No network access:** The application does **not** declare Android's `INTERNET` or `ACCESS_NETWORK_STATE` permissions and makes zero outbound network connections.
- **No accounts, analytics, ads, or crash-upload SDKs:** The application contains no analytics SDKs, advertising SDKs, tracking libraries, or remote crash-reporting services.
- **On-device storage only:** All game indexes, battery saves (SRAM), save states, screenshots, cheat entries, controller mappings, layout profiles, and optional BIOS files remain strictly inside your device's local storage. Nothing is uploaded.
- **Optional storage access:** The device-wide **Scan device** action asks for storage read access. On Android 9 and below this is the standard `READ_EXTERNAL_STORAGE` dialog; on Android 10 and above it is Android's **All files access** setting (`MANAGE_EXTERNAL_STORAGE`). You can decline and keep using SAF folders and single-file import; the app never scans device storage without that access.

## 2. Files and Permissions

- The application uses Android's **Storage Access Framework (SAF)** (`OpenDocument`, `OpenDocumentTree`, and `CreateDocument`) for all file picking, saving, and backup operations.
- **Optional device scan:** If you tap **Scan device**, the app asks for `READ_EXTERNAL_STORAGE` (Android 9 and below) or asks you to enable **All files access** (`MANAGE_EXTERNAL_STORAGE`, Android 10 and above) in system Settings. This is used **only** to look for `.gba`, `.gbc`, and `.gb` files and read their headers/checksums so they can be added to the local library. The app does not request `INTERNET`, `WRITE_EXTERNAL_STORAGE`, or any media-location permission, and never modifies or deletes files found on shared storage.
- If you decline or later revoke storage access, the app still works through SAF: choose a ROM folder or a single ROM file with the system picker. Previously discovered file-backed entries are marked unavailable until access is restored or you relink them; their saves stay intact.
- You can revoke folder access at any time by removing a folder in the **Settings** tab, and revoke All files access in Android system Settings.

## 3. Data Stored Locally on Your Device

To provide emulator functionality, the app stores the following strictly on your device inside app-private storage (`filesDir`, `cacheDir`, Room database, and DataStore):

- **Library metadata:** Game SHA-256 checksums, detected system (`GBA`/`GBC`/`GB`), titles, custom titles, favourites, play time, and persisted SAF document URIs.
- **Saves & thumbnails:** Per-game battery saves (`.sav`), 8-slot save states (`.state`, `.meta`, `.bak`), and save-state PNG thumbnails captured locally from the emulated screen.
- **Preferences & profiles:** Audio/video preferences, portrait & landscape touch layout settings, per-game cheat codes, core option overrides, and external Bluetooth/USB controller button mappings.
- **Optional user-supplied BIOS files:** Only if you explicitly import them via the Extras tab.

## 4. Data Deletion and Uninstall Notice

- Uninstalling the application causes Android to permanently delete all app-private data, including your battery saves and save states.
- Before uninstalling or switching devices, use **Export Battery Save (.sav)** from the Home library menu to store a copy in a location of your choice.
- Inside the app, removing a game from the library never deletes your original ROM file on storage; deleting saves requires a separate explicit confirmation.

## 5. Third-Party Components

This app bundles the open-source **mGBA Libretro core** (`MPL-2.0`) and standard AndroidX / Jetpack Compose libraries (`Apache-2.0`). None of these components transmit user data off the device. See `THIRD_PARTY_NOTICES.md` for full licence details.

## 6. Contact

For privacy questions or bug reports, open an issue on the project repository:
`https://github.com/sumankayal247/GBA-emulator-app/issues`
